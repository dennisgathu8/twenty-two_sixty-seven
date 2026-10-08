(ns bwr.main-test
  "Unit tests for system lifecycle management and skeleton HTTP endpoints in bwr.main."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [bwr.main :as main]
            [org.httpkit.client :as http]))

(defn with-clean-system [f]
  (try
    (f)
    (finally
      (main/stop!))))

(use-fixtures :each with-clean-system)

(deftest system-lifecycle-test
  (testing "Initial state is not running"
    (is (false? (:running? (main/system-status)))))

  (testing "Starting system sets running? to true and starts HTTP server"
    (is (= :started (main/start!)))
    (is (true? (:running? (main/system-status))))
    (is (= "System is already running on port 3000."
           (clojure.string/trim (with-out-str (main/start!))))))

  (testing "HTTP endpoints respond while running"
    (let [{:keys [status body]} @(http/get "http://localhost:3000/")
          html (if (string? body) body (slurp body :encoding "UTF-8"))]
      (is (= 200 status))
      (is (clojure.string/includes? html "Break-Window Response")))
    (let [{:keys [status body]} @(http/get "http://localhost:3000/health")
          edn (if (string? body) body (slurp body :encoding "UTF-8"))]
      (is (= 200 status))
      (is (clojure.string/includes? edn ":status :ok"))))

  (testing "Stopping system returns :stopped and clears state"
    (is (= :stopped (main/stop!)))
    (is (false? (:running? (main/system-status))))
    (is (= :not-running (main/stop!)))))

(deftest rate-limit-config-test
  (testing "Defaults to 100 requests per 60s when environment variables are unset or blank"
    (is (= {:max-requests 100 :window-seconds 60}
           (main/resolve-rate-limit-config {})))
    (is (= {:max-requests 100 :window-seconds 60}
           (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_MAX" ""
                                            "BWR_RATE_LIMIT_WINDOW_S" "   "}))))

  (testing "Parses valid integer strings from environment"
    (is (= {:max-requests 500 :window-seconds 120}
           (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_MAX" "500"
                                            "BWR_RATE_LIMIT_WINDOW_S" "120"})))
    (is (= {:max-requests 10000 :window-seconds 60}
           (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_MAX" "10000"}))))

  (testing "Rejects invalid non-positive or non-numeric values with clojure.spec ExceptionInfo"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_MAX" "0"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_MAX" "-10"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_MAX" "not-a-number"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_WINDOW_S" "0"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_WINDOW_S" "-5"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                          (main/resolve-rate-limit-config {"BWR_RATE_LIMIT_WINDOW_S" "abc"})))))

(deftest rate-limiter-threshold-enforcement-test
  (testing "Limiter returns 429 with Retry-After at configured threshold"
    (main/stop!)
    (is (= :started (main/start! {:port 3000
                                  :topology :in-memory
                                  :rate-limit-opts {:max-requests 2 :window-seconds 30}})))
    (try
      ;; First 2 requests within budget
      (let [r1 @(http/get "http://localhost:3000/health")
            r2 @(http/get "http://localhost:3000/health")]
        (is (= 200 (:status r1)))
        (is (= 200 (:status r2))))

      ;; 3rd request trips the threshold
      (let [{:keys [status headers body]} @(http/get "http://localhost:3000/health")
            body-str (if (string? body) body (slurp body :encoding "UTF-8"))]
        (is (= 429 status))
        (is (some? (or (get headers :retry-after) (get headers "retry-after"))))
        (is (clojure.string/includes? body-str "Rate limit exceeded")))
      (finally
        (main/stop!)))))

(deftest trusted-proxies-config-test
  (testing "Defaults to empty set when environment variable is unset or blank"
    (is (= #{} (main/resolve-trusted-proxies {})))
    (is (= #{} (main/resolve-trusted-proxies {"BWR_TRUSTED_PROXIES" ""})))
    (is (= #{} (main/resolve-trusted-proxies {"BWR_TRUSTED_PROXIES" "   "}))))

  (testing "Parses comma-separated list of valid IPv4 and IPv6 literals"
    (is (= #{"127.0.0.1" "10.0.0.1"}
           (main/resolve-trusted-proxies {"BWR_TRUSTED_PROXIES" "127.0.0.1, 10.0.0.1"})))
    (is (= #{"127.0.0.1" "::1" "192.168.1.1"}
           (main/resolve-trusted-proxies {"BWR_TRUSTED_PROXIES" "127.0.0.1, ::1, 192.168.1.1"}))))

  (testing "Throws ExceptionInfo when any entry in BWR_TRUSTED_PROXIES is not a valid IP literal"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid IP literal"
                          (main/resolve-trusted-proxies {"BWR_TRUSTED_PROXIES" "127.0.0.1, not-an-ip"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid IP literal"
                          (main/resolve-trusted-proxies {"BWR_TRUSTED_PROXIES" "300.1.1.1"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid IP literal"
                          (main/resolve-trusted-proxies {"BWR_TRUSTED_PROXIES" "<script>alert(1)</script>"})))))

(deftest bind-address-config-test
  (testing "Defaults to 127.0.0.1 loopback when unset or blank"
    (is (= "127.0.0.1" (main/resolve-bind-address {})))
    (is (= "127.0.0.1" (main/resolve-bind-address {"BWR_BIND" ""})))
    (is (= "127.0.0.1" (main/resolve-bind-address {"BWR_BIND" "   "}))))

  (testing "Parses valid IP literal strings"
    (is (= "127.0.0.2" (main/resolve-bind-address {"BWR_BIND" "127.0.0.2"})))
    (is (= "0.0.0.0" (main/resolve-bind-address {"BWR_BIND" "0.0.0.0"})))
    (is (= "::1" (main/resolve-bind-address {"BWR_BIND" "::1"})))
    (is (= "::1" (main/resolve-bind-address {"BWR_BIND" "[::1]"}))))

  (testing "Throws ExceptionInfo when BWR_BIND is not a valid IP literal"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid IP literal"
                          (main/resolve-bind-address {"BWR_BIND" "not-an-ip"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid IP literal"
                          (main/resolve-bind-address {"BWR_BIND" "300.1.1.1"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a valid IP literal"
                          (main/resolve-bind-address {"BWR_BIND" "<script>"})))))

(deftest test-mode-non-loopback-bind-guard-test
  (testing "Refuses to start with clear ExceptionInfo if BWR_ENV=test and bind address is not loopback"
    (with-redefs [bwr.auth.session/test-environment? (constantly true)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be bound to non-loopback"
                            (main/start! {:bind "0.0.0.0" :port 3000 :topology :in-memory})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be bound to non-loopback"
                            (main/start! {:bind "192.168.1.50" :port 3000 :topology :in-memory}))))))

(deftest relaxed-rate-limit-detection-test
  (testing "Detects relaxed rate limit by effective rate (max * 60 / window > 100)"
    ;; Exactly 100 req/min -> not relaxed
    (is (false? (main/relaxed-rate-limit? {:max-requests 100 :window-seconds 60})))
    (is (false? (main/relaxed-rate-limit? {:max-requests 200 :window-seconds 120})))
    (is (false? (main/relaxed-rate-limit? {:max-requests 10 :window-seconds 6})))

    ;; Under 100 req/min -> not relaxed
    (is (false? (main/relaxed-rate-limit? {:max-requests 50 :window-seconds 60})))

    ;; Over 100 req/min -> relaxed!
    (is (true? (main/relaxed-rate-limit? {:max-requests 100 :window-seconds 1})))   ; 6000 req/min
    (is (true? (main/relaxed-rate-limit? {:max-requests 50 :window-seconds 10})))   ; 300 req/min
    (is (true? (main/relaxed-rate-limit? {:max-requests 10000 :window-seconds 60}))) ; 10000 req/min
    (is (true? (main/relaxed-rate-limit? {:max-requests 201 :window-seconds 120})))) ; 100.5 req/min

  (testing "Emits loud banner when effective rate exceeds threshold"
    (let [out-str (with-out-str
                    (binding [*err* *out*]
                      (#'main/log-rate-limiter-config! {:max-requests 100 :window-seconds 1})))]
      (is (clojure.string/includes? out-str "ATTENTION: RELAXED RATE LIMIT CONFIGURED AT STARTUP!"))
      (is (clojure.string/includes? out-str "100 REQS / 1S (~6000 REQS/MIN)")))

    (let [out-str (with-out-str
                    (binding [*err* *out*]
                      (#'main/log-rate-limiter-config! {:max-requests 100 :window-seconds 60})))]
      (is (not (clojure.string/includes? out-str "ATTENTION: RELAXED RATE LIMIT CONFIGURED AT STARTUP!")))
      (is (clojure.string/includes? out-str "Rate limiter active: 100 requests per 60s.")))))
