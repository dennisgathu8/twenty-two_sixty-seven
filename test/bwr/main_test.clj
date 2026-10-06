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
