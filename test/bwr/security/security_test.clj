(ns bwr.security.security-test
  "Tests for security middleware, live IP containment, and rate limiting (§9):
   - Shared security headers & strict CSP without unsafe-inline
   - Live IP banning containment lever and security event logging
   - Sliding-window rate limiting per IP and 429 rejection."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [bwr.store.node :as store-node]
            [bwr.store.query :as store-query]
            [bwr.security.middleware :as sec]))

(def ^:dynamic *node* nil)

(defn with-clean-security [f]
  (let [node (store-node/start-node! {:topology :in-memory})]
    (try
      (sec/reset-banned-ips!)
      (binding [*node* node]
        (f))
      (finally
        (sec/reset-banned-ips!)
        (store-node/stop-node! node)))))

(use-fixtures :each with-clean-security)

;; ============================================================================
;; 1. Reusable Security Headers Middleware (§9.3)
;; ============================================================================

(deftest wrap-security-headers-test
  (testing "Applies strict Content-Security-Policy without unsafe-inline and standard security headers"
    (let [base-handler (fn [_] {:status 200 :headers {"Content-Type" "text/plain"} :body "ok"})
          wrapped-handler (sec/wrap-security-headers base-handler)
          resp (wrapped-handler {:request-method :get :uri "/"})
          headers (:headers resp)
          csp (get headers "Content-Security-Policy")]

      (is (= 200 (:status resp)))
      (is (contains? headers "Content-Security-Policy"))
      (is (str/includes? csp "default-src 'self'"))
      (is (str/includes? csp "script-src 'self'"))
      (is (str/includes? csp "style-src 'self'"))
      (is (not (str/includes? csp "unsafe-inline")))
      (is (= "nosniff" (get headers "X-Content-Type-Options")))
      (is (= "DENY" (get headers "X-Frame-Options")))
      (is (= "strict-origin-when-cross-origin" (get headers "Referrer-Policy"))))))

;; ============================================================================
;; 2. Live IP Containment Levers (§9.4, §9.7)
;; ============================================================================

(deftest ip-containment-test
  (testing "Banned IP receives HTTP 403 Forbidden with security headers and logs containment event"
    (let [base-handler (fn [_] {:status 200 :body "welcome"})
          wrapped-handler (sec/wrap-ip-containment base-handler *node*)
          normal-req {:remote-addr "192.168.1.100" :request-method :get :uri "/"}
          attacker-req {:remote-addr "198.51.100.25" :request-method :get :uri "/admin"}]

      ;; 1. Normal access permitted
      (let [resp (wrapped-handler normal-req)]
        (is (= 200 (:status resp)))
        (is (= "welcome" (:body resp))))

      ;; 2. Activate Containment Lever: Ban attacker IP
      (sec/ban-ip! *node* "198.51.100.25" "Repeated malicious auth probes")
      (is (true? (sec/ip-banned? "198.51.100.25")))

      ;; 3. Attacker access blocked immediately
      (let [resp (wrapped-handler attacker-req)]
        (is (= 403 (:status resp)))
        (is (str/includes? (:body resp) "Forbidden"))
        (is (contains? (:headers resp) "Content-Security-Policy")))

      ;; 4. Verify security event audit trail
      (let [db (store-query/db-at *node*)
            ban-events (store-query/find-security-events db {:type :sec.type/ip-banned})
            blocked-events (store-query/find-security-events db {:type :sec.type/containment-blocked})]
        (is (= 1 (count ban-events)))
        (is (= "198.51.100.25" (:sec-event/client-ip (first ban-events))))
        (is (= 1 (count blocked-events)))
        (is (= "198.51.100.25" (:sec-event/client-ip (first blocked-events))))
        (is (= :reason/ip-banned (:sec-event/reason (first blocked-events)))))

      ;; 5. Unban restores access
      (sec/unban-ip! *node* "198.51.100.25")
      (is (false? (sec/ip-banned? "198.51.100.25")))
      (let [resp (wrapped-handler attacker-req)]
        (is (= 200 (:status resp)))))))

;; ============================================================================
;; 3. Rate Limiting Containment Lever (§9.4)
;; ============================================================================

(deftest rate-limiting-test
  (testing "Rate limiter permits requests within budget and blocks excess with HTTP 429"
    (let [limiter (sec/create-rate-limiter {:max-requests 3 :window-seconds 60})
          base-handler (fn [_] {:status 200 :body "ok"})
          wrapped-handler (sec/wrap-rate-limit base-handler limiter *node*)
          req {:remote-addr "203.0.113.42" :request-method :get :uri "/auth/request"}]

      ;; First 3 requests succeed
      (dotimes [_ 3]
        (let [resp (wrapped-handler req)]
          (is (= 200 (:status resp)))))

      ;; 4th request exceeds rate limit: HTTP 429
      (let [resp (wrapped-handler req)]
        (is (= 429 (:status resp)))
        (is (str/includes? (:body resp) "Rate limit exceeded"))
        (is (contains? (:headers resp) "Retry-After"))
        (is (contains? (:headers resp) "Content-Security-Policy")))

      ;; Audit log records rate limit trip
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/rate-limit-exceeded})]
        (is (= 1 (count events)))
        (is (= "203.0.113.42" (:sec-event/client-ip (first events))))
        (is (= :reason/rate-limit (:sec-event/reason (first events))))))))

;; ============================================================================
;; 4. IP Literal Parsing & Loopback Detection (§9.4, ADR-003)
;; ============================================================================

(deftest ip-literal-validation-test
  (testing "Validates strict IPv4 literals without DNS resolution"
    (is (true? (sec/valid-ipv4? "127.0.0.1")))
    (is (true? (sec/valid-ipv4? "192.168.1.100")))
    (is (true? (sec/valid-ipv4? "10.0.0.1")))
    (is (true? (sec/valid-ipv4? "0.0.0.0")))
    (is (true? (sec/valid-ipv4? "255.255.255.255")))

    ;; Invalid IPv4 literals
    (is (false? (sec/valid-ipv4? "300.1.1.1")))
    (is (false? (sec/valid-ipv4? "01.1.1.1")))       ; leading zero rejected
    (is (false? (sec/valid-ipv4? "1.2.3")))          ; 3 octets
    (is (false? (sec/valid-ipv4? "1.2.3.4.5")))      ; 5 octets
    (is (false? (sec/valid-ipv4? "1.2.3.a")))
    (is (false? (sec/valid-ipv4? "-1.0.0.1")))
    (is (false? (sec/valid-ipv4? "")))
    (is (false? (sec/valid-ipv4? nil))))

  (testing "Validates strict IPv6 literals without DNS resolution"
    (is (true? (sec/valid-ipv6? "::1")))
    (is (true? (sec/valid-ipv6? "[::1]")))
    (is (true? (sec/valid-ipv6? "::")))
    (is (true? (sec/valid-ipv6? "fe80::1")))
    (is (true? (sec/valid-ipv6? "2001:db8::1")))
    (is (true? (sec/valid-ipv6? "1:2:3:4:5:6:7:8")))
    (is (true? (sec/valid-ipv6? "::ffff:192.168.1.1")))
    (is (true? (sec/valid-ipv6? "1:2:3:4:5:6:1.2.3.4")))

    ;; Invalid IPv6 literals & non-IP input
    (is (false? (sec/valid-ipv6? ":::1")))           ; triple colon
    (is (false? (sec/valid-ipv6? "fe80::1::2")))     ; multiple ::
    (is (false? (sec/valid-ipv6? "1:2:3:4:5:6:7:8:9"))) ; 9 groups
    (is (false? (sec/valid-ipv6? "1:2:3:4:5:6:7")))   ; 7 groups without ::
    (is (false? (sec/valid-ipv6? "gggg::1")))        ; non-hex
    (is (false? (sec/valid-ipv6? "<script>alert(1)</script>")))
    (is (false? (sec/valid-ipv6? "attacker.com")))
    (is (false? (sec/valid-ipv6? "localhost")))
    (is (false? (sec/valid-ipv6? "")))
    (is (false? (sec/valid-ipv6? nil))))

  (testing "Combined valid-ip-literal? and extract-ip-literal"
    (is (true? (sec/valid-ip-literal? "127.0.0.1")))
    (is (true? (sec/valid-ip-literal? "::1")))
    (is (false? (sec/valid-ip-literal? "attacker.com")))
    (is (false? (sec/valid-ip-literal? "300.1.1.1")))

    (is (= "192.168.1.100" (sec/extract-ip-literal "192.168.1.100:3000")))
    (is (= "::1" (sec/extract-ip-literal "[::1]:8080")))
    (is (= "::1" (sec/extract-ip-literal "[::1]")))
    (is (nil? (sec/extract-ip-literal "<script>")))
    (is (nil? (sec/extract-ip-literal "attacker.com")))))

(deftest loopback-ip-test
  (testing "Detects loopback addresses across IPv4 and IPv6 representations"
    (is (true? (sec/loopback-ip? "127.0.0.1")))
    (is (true? (sec/loopback-ip? "127.0.0.2")))
    (is (true? (sec/loopback-ip? "::1")))
    (is (true? (sec/loopback-ip? "[::1]")))
    (is (true? (sec/loopback-ip? "0:0:0:0:0:0:0:1")))

    ;; Non-loopback addresses
    (is (false? (sec/loopback-ip? "0.0.0.0")))
    (is (false? (sec/loopback-ip? "192.168.1.1")))
    (is (false? (sec/loopback-ip? "10.0.0.1")))
    (is (false? (sec/loopback-ip? "attacker.com")))))

(deftest ip-predicates-boolean-regression-test
  (testing "IP predicates always return strict boolean (never nil or truthy non-boolean)"
    (doseq [pred [sec/valid-ipv4?
                  sec/valid-ipv6?
                  sec/valid-ip-literal?
                  sec/loopback-ip?]
            input [nil "" 5 "abc" "1.2.3" "::1" "127.0.0.1"]]
      (is (boolean? (pred input))
          (str "Expected boolean from " pred " on input: " (pr-str input))))))

;; ============================================================================
;; 5. Client-IP Trust Model & Traversal Algorithm (§9.4, ADR-003)
;; ============================================================================

(deftest resolve-client-ip-trust-model-test
  (testing "Untrusted remote with spoofed X-Forwarded-For: spoof ignored, real address used"
    (let [req {:remote-addr "203.0.113.195"
               :headers {"x-forwarded-for" "10.0.0.1, 192.168.1.1"
                         "x-real-ip" "10.0.0.1"}}
          trusted-proxies #{"127.0.0.1"}]
      (is (= "203.0.113.195" (sec/resolve-client-ip req trusted-proxies)))))

  (testing "Trusted proxy with chain '1.2.3.4, <real>': real address wins, leftmost spoof ignored"
    (let [req {:remote-addr "127.0.0.1"
               :headers {"x-forwarded-for" "1.2.3.4, 198.51.100.50"}}
          trusted-proxies #{"127.0.0.1"}]
      (is (= "198.51.100.50" (sec/resolve-client-ip req trusted-proxies)))))

  (testing "Trusted proxy chain with multiple trusted hops: skips trusted proxies from right"
    (let [req {:remote-addr "127.0.0.1"
               :headers {"x-forwarded-for" "1.1.1.1, 203.0.113.88, 10.0.0.2"}}
          trusted-proxies #{"127.0.0.1" "10.0.0.2"}]
      (is (= "203.0.113.88" (sec/resolve-client-ip req trusted-proxies)))))

  (testing "Garbage entries (<script>, hostname, 300.1.1.1) ignored; falls back to remote-addr"
    (let [req {:remote-addr "127.0.0.1"
               :headers {"x-forwarded-for" "<script>, attacker.com, 300.1.1.1"}}
          trusted-proxies #{"127.0.0.1"}]
      (is (= "127.0.0.1" (sec/resolve-client-ip req trusted-proxies)))))

  (testing "Handles IPv6 addresses in trusted proxy chain"
    (let [req {:remote-addr "::1"
               :headers {"x-forwarded-for" "2001:db8::1, ::1"}}
          trusted-proxies #{"127.0.0.1" "::1"}]
      (is (= "2001:db8::1" (sec/resolve-client-ip req trusted-proxies)))))

  (testing "Fallback to X-Real-IP when X-Forwarded-For absent and remote is trusted"
    (let [req {:remote-addr "127.0.0.1"
               :headers {"x-real-ip" "198.51.100.77"}}
          trusted-proxies #{"127.0.0.1"}]
      (is (= "198.51.100.77" (sec/resolve-client-ip req trusted-proxies)))))

  (testing "Fallback to 'unknown' when remote-addr is nil or invalid"
    (let [req {:headers {"x-forwarded-for" "1.2.3.4"}}]
      (is (= "unknown" (sec/resolve-client-ip req #{}))))))

(deftest rate-limiting-ip-spoof-containment-test
  (testing "Two requests from one untrusted remote with different spoofed headers share a single rate-limit bucket"
    (let [limiter (sec/create-rate-limiter {:max-requests 2 :window-seconds 60})
          base-handler (fn [req] {:status 200 :body (str "client:" (:bwr/client-ip req))})
          app (-> base-handler
                  (sec/wrap-rate-limit limiter *node*)
                  (sec/wrap-client-ip #{}))  ; Trust nothing by default
          req1 {:remote-addr "198.51.100.99"
                :headers {"x-forwarded-for" "10.0.0.1"}
                :request-method :get :uri "/auth/request"}
          req2 {:remote-addr "198.51.100.99"
                :headers {"x-forwarded-for" "10.0.0.2"}
                :request-method :get :uri "/auth/request"}
          req3 {:remote-addr "198.51.100.99"
                :headers {"x-forwarded-for" "10.0.0.3"}
                :request-method :get :uri "/auth/request"}]

      ;; Request 1 with spoofed header -> allowed under 198.51.100.99
      (let [r1 (app req1)]
        (is (= 200 (:status r1)))
        (is (= "client:198.51.100.99" (:body r1))))

      ;; Request 2 with different spoofed header -> allowed under 198.51.100.99
      (let [r2 (app req2)]
        (is (= 200 (:status r2)))
        (is (= "client:198.51.100.99" (:body r2))))

      ;; Request 3 with third spoofed header -> rate limit exceeded (bucket exhausted!)
      (let [r3 (app req3)]
        (is (= 429 (:status r3)))
        (is (str/includes? (:body r3) "Rate limit exceeded")))

      ;; Verify security audit trail recorded the genuine remote IP, not the spoofed IP
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/rate-limit-exceeded})]
        (is (= 1 (count events)))
        (is (= "198.51.100.99" (:sec-event/client-ip (first events))))))))
