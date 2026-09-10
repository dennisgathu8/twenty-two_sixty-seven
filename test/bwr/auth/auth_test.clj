(ns bwr.auth.auth-test
  "Comprehensive tests for passwordless HMAC magic-link authentication (§3, §9.2):
   - Token generation and inspectable email delivery
   - Single-use verification and atomic invalidation
   - Rejection on reuse with distinguishable reason (:reason/already-consumed)
   - Expiration rejection (:reason/expired)
   - Signature tampering rejection before store lookup (:reason/invalid-signature)
   - Queryable security audit trail logging in XTDB storage (§2.4)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [xtdb.api :as xt]
            [bwr.store.node :as store-node]
            [bwr.store.query :as store-query]
            [bwr.auth.email :as email]
            [bwr.auth.magic-link :as magic-link]
            [bwr.auth.session :as session]
            [bwr.security.middleware :as sec]))

(def ^:dynamic *node* nil)

(defn with-clean-auth [f]
  (let [node (store-node/start-node! {:topology :in-memory})]
    (try
      (email/clear-spool!)
      (binding [*node* node]
        (f))
      (finally
        (email/clear-spool!)
        (store-node/stop-node! node)))))

(use-fixtures :each with-clean-auth)

(def test-secret "test-hmac-secret-key-32-chars-long-for-tests-1234")

;; ============================================================================
;; 1. Token Generation & Inspectable Email Delivery
;; ============================================================================

(deftest token-generation-and-email-delivery-test
  (testing "Generates HMAC-signed magic link, records in XTDB, logs audit event, and spools email"
    (let [res (magic-link/generate-magic-link!
               *node*
               {:identity "coach@bwr.lan"
                :secret test-secret
                :ttl-seconds 900
                :client-ip "192.168.1.50"
                :send-email? true})
          token (:token res)
          jti (:jti res)
          link (:magic-link-url res)]

      (is (string? token))
      (is (string? jti))
      (is (str/includes? link token))
      (is (str/starts-with? link "https://breakwindow.lan/auth/verify?token="))

      ;; 1. XTDB Token Entity
      (let [db (store-query/db-at *node*)
            doc (store-query/entity db (str "token-" jti))]
        (is (some? doc))
        (is (= "coach@bwr.lan" (:token/identity doc)))
        (is (false? (:token/consumed? doc)))
        (is (nil? (:token/consumed-at doc))))

      ;; 2. Security Event Audit Trail in XTDB
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/magic-link-generated})]
        (is (= 1 (count events)))
        (let [evt (first events)]
          (is (= "coach@bwr.lan" (:sec-event/identity evt)))
          (is (= "192.168.1.50" (:sec-event/client-ip evt)))
          (is (= :status/success (:sec-event/status evt)))
          (is (= jti (get-in evt [:sec-event/detail :jti])))))

      ;; 3. Inspectable Email Delivery Content
      (let [spooled (email/get-spooled-emails)]
        (is (= 1 (count spooled)))
        (let [mail (first spooled)]
          (is (= "coach@bwr.lan" (:to mail)))
          (is (= "Your Break-Window Response Access Link" (:subject mail)))
          (is (str/includes? (:body-text mail) link))
          (is (str/includes? (:body-html mail) link))
          (is (str/includes? (:body-text mail) "expire in 15 minutes")))))))

;; ============================================================================
;; 2. Single-Use Verification & Distinguishable Rejection Reasons
;; ============================================================================

(deftest single-use-and-atomic-invalidation-test
  (testing "Single-use lifecycle: first verification succeeds, reuse is rejected with :reason/already-consumed"
    (let [res (magic-link/generate-magic-link!
               *node*
               {:identity "analyst@bwr.lan"
                :secret test-secret
                :client-ip "10.0.0.1"})
          token (:token res)
          jti (:jti res)]

      ;; First Use: Should Succeed
      (let [verify-1 (magic-link/verify-and-consume! *node* token {:secret test-secret :client-ip "10.0.0.1"})]
        (is (true? (:valid? verify-1)))
        (is (= "analyst@bwr.lan" (:identity verify-1)))
        (is (= jti (:jti verify-1)))
        (is (some? (:consumed-at verify-1))))

      ;; Store State: token marked consumed
      (let [db (store-query/db-at *node*)
            doc (store-query/entity db (str "token-" jti))]
        (is (true? (:token/consumed? doc)))
        (is (some? (:token/consumed-at doc))))

      ;; Second Use (Replay): Must Be Rejected
      (let [verify-2 (magic-link/verify-and-consume! *node* token {:secret test-secret :client-ip "10.0.0.2"})]
        (is (false? (:valid? verify-2)))
        (is (= :reason/already-consumed (:reason verify-2)))
        (is (str/includes? (:error verify-2) "already been used"))
        (is (some? (:consumed-at verify-2))))

      ;; Verify Audit Trail shows generation, verification, and rejected reuse
      (let [db (store-query/db-at *node*)
            all-events (store-query/find-security-events db {:identity "analyst@bwr.lan"})]
        (is (= 3 (count all-events)))
        (let [types (mapv :sec-event/type (reverse all-events))
              statuses (mapv :sec-event/status (reverse all-events))]
          (is (= [:sec.type/magic-link-generated
                  :sec.type/magic-link-verified
                  :sec.type/magic-link-rejected]
                 types))
          (is (= [:status/success :status/success :status/failure] statuses)))))))

;; ============================================================================
;; 3. Expiration Rejection
;; ============================================================================

(deftest expired-token-rejection-test
  (testing "Token past its TTL is rejected with :reason/expired, distinguished from already-consumed"
    (let [res (magic-link/generate-magic-link!
               *node*
               {:identity "coach@bwr.lan"
                :secret test-secret
                :ttl-seconds -10 ;; issued already expired
                :client-ip "10.0.0.5"})
          token (:token res)]

      (let [verify-res (magic-link/verify-and-consume! *node* token {:secret test-secret :client-ip "10.0.0.5"})]
        (is (false? (:valid? verify-res)))
        (is (= :reason/expired (:reason verify-res)))
        (is (str/includes? (:error verify-res) "expired")))

      ;; Audit log records expired rejection
      (let [db (store-query/db-at *node*)
            rejected-events (store-query/find-security-events db {:type :sec.type/magic-link-rejected})]
        (is (= 1 (count rejected-events)))
        (is (= :reason/expired (:sec-event/reason (first rejected-events))))))))

;; ============================================================================
;; 4. Signature Tampering Rejection
;; ============================================================================

(deftest signature-tampering-test
  (testing "Tampered token signature is rejected before store lookup, leaving original token intact"
    (let [res (magic-link/generate-magic-link!
               *node*
               {:identity "admin@bwr.lan"
                :secret test-secret
                :client-ip "10.0.0.10"})
          token (:token res)
          jti (:jti res)
          ;; Flip the final character of the signature
          last-char (str (last token))
          replacement (if (= last-char "a") "b" "a")
          tampered-token (str (subs token 0 (dec (count token))) replacement)]

      ;; Verification of tampered token must fail
      (let [verify-res (magic-link/verify-and-consume! *node* tampered-token {:secret test-secret :client-ip "192.168.1.99"})]
        (is (false? (:valid? verify-res)))
        (is (= :reason/invalid-signature (:reason verify-res)))
        (is (str/includes? (:error verify-res) "tampered")))

      ;; Original token in XTDB was NOT consumed
      (let [db (store-query/db-at *node*)
            doc (store-query/entity db (str "token-" jti))]
        (is (false? (:token/consumed? doc))))

      ;; Security event was logged for the tampering attempt
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/magic-link-rejected})]
        (is (= 1 (count events)))
        (is (= :reason/invalid-signature (:sec-event/reason (first events))))
        (is (= "192.168.1.99" (:sec-event/client-ip (first events))))))))

;; ============================================================================
;; 5. Session Issuance, Gating, and Invalidation Lifecycle (§9.2, §9.4)
;; ============================================================================

(deftest session-lifecycle-and-gating-test
  (testing "End-to-end 6-step authentication lifecycle:
            1. Token generated
            2. Token verified + consumed (one-time)
            3. Session issued (XTDB record + token)
            4. Subsequent request with session -> succeeds
            5. Subsequent request with original one-time token -> rejected (already consumed)
            6. Session invalidated (logout) -> subsequent request with session -> rejected"
    (let [identity-email "coach@breakwindow.lan"
          ;; Protected mock handler representing Step 7 admin route
          protected-handler (fn [req]
                              {:status 200
                               :headers {"Content-Type" "text/plain"}
                               :body (str "Hello authenticated coach: " (:identity req))})
          auth-gated-handler (session/wrap-require-auth protected-handler *node* {:secret test-secret})]

      ;; Step 1: Token generated
      (let [gen-res (magic-link/generate-magic-link!
                     *node*
                     {:identity identity-email
                      :secret test-secret
                      :ttl-seconds 900
                      :client-ip "10.0.1.5"})
            magic-token (:token gen-res)
            jti (:jti gen-res)]

        (is (string? magic-token))

        ;; Step 2 & 3: Token verified + consumed (one-time) and Session issued
        (let [handshake (session/verify-magic-link-and-create-session!
                         *node*
                         magic-token
                         {:secret test-secret
                          :client-ip "10.0.1.5"})]
          (is (true? (:valid? handshake)))
          (is (= identity-email (:identity handshake)))
          (is (some? (:session-id handshake)))
          (is (some? (:session-token handshake)))

          (let [session-token (:session-token handshake)
                session-id (:session-id handshake)]

            ;; Verify session document exists in XTDB
            (let [db (store-query/db-at *node*)
                  sess-doc (store-query/entity db session-id)]
              (is (some? sess-doc))
              (is (= identity-email (:session/identity sess-doc)))
              (is (false? (:session/revoked? sess-doc))))

            ;; Step 4: Subsequent request with session cookie -> succeeds
            (let [req-with-cookie {:request-method :get
                                   :uri "/admin/rules"
                                   :headers {"cookie" (str "bwr_session=" session-token)}
                                   :remote-addr "10.0.1.5"}
                  resp (auth-gated-handler req-with-cookie)]
              (is (= 200 (:status resp)))
              (is (str/includes? (:body resp) identity-email)))

            ;; Step 4b: Subsequent request with Authorization Bearer header -> also succeeds
            (let [req-with-bearer {:request-method :get
                                   :uri "/admin/rules"
                                   :headers {"authorization" (str "Bearer " session-token)}
                                   :remote-addr "10.0.1.5"}
                  resp (auth-gated-handler req-with-bearer)]
              (is (= 200 (:status resp)))
              (is (str/includes? (:body resp) identity-email)))

            ;; Step 5: Subsequent request with original one-time magic token -> rejected (already consumed)
            (let [replay-verify (magic-link/verify-and-consume! *node* magic-token {:secret test-secret})]
              (is (false? (:valid? replay-verify)))
              (is (= :reason/already-consumed (:reason replay-verify))))

            ;; Gated handler rejects request using original magic-link token as session
            (let [req-with-consumed-token {:request-method :get
                                           :uri "/admin/rules"
                                           :headers {"authorization" (str "Bearer " magic-token)}
                                           :remote-addr "10.0.1.5"}
                  resp (auth-gated-handler req-with-consumed-token)]
              (is (= 401 (:status resp)))
              (is (str/includes? (:body resp) "Unauthorized")))

            ;; Step 6: Session invalidated (logout / revocation)
            (let [revoked? (session/revoke-session! *node* session-token {:secret test-secret :reason :reason/user-logout})]
              (is (true? revoked?)))

            ;; Subsequent request with revoked session -> rejected (HTTP 401)
            (let [req-after-logout {:request-method :get
                                    :uri "/admin/rules"
                                    :headers {"cookie" (str "bwr_session=" session-token)}
                                    :remote-addr "10.0.1.5"}
                  resp (auth-gated-handler req-after-logout)]
              (is (= 401 (:status resp)))
              (is (str/includes? (:body resp) "Unauthorized")))

            ;; Confirm XTDB recorded session revocation security event
            (let [db (store-query/db-at *node*)
                  revoked-events (store-query/find-security-events db {:type :sec.type/session-revoked})]
              (is (= 1 (count revoked-events)))
              (is (= identity-email (:sec-event/identity (first revoked-events))))
              (is (= :reason/user-logout (:sec-event/reason (first revoked-events)))))))))))

;; ============================================================================
;; 6. Magic Link Rate Limiting per IP and per Identity (§9.4, §9.7)
;; ============================================================================

(deftest magic-link-rate-limiting-test
  (testing "Enforces sliding-window rate limits on magic link requests per IP and identity"
    (let [ip-limiter (sec/create-rate-limiter {:max-requests 2 :window-seconds 60})
          ident-limiter (sec/create-rate-limiter {:max-requests 2 :window-seconds 60})]

      ;; First 2 requests succeed
      (dotimes [_ 2]
        (let [res (magic-link/generate-magic-link!
                   *node*
                   {:identity "coach@breakwindow.lan"
                    :secret test-secret
                    :client-ip "172.16.0.10"
                    :ip-limiter ip-limiter
                    :identity-limiter ident-limiter
                    :send-email? false})]
          (is (true? (:authorized? res)))
          (is (string? (:token res)))))

      ;; 3rd request from same IP trips rate limit with HTTP 429
      (let [err (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo
                     #"Rate limit exceeded for client IP"
                     (magic-link/generate-magic-link!
                      *node*
                      {:identity "analyst@breakwindow.lan"
                       :secret test-secret
                       :client-ip "172.16.0.10"
                       :ip-limiter ip-limiter
                       :identity-limiter ident-limiter
                       :send-email? false})))]
        (let [data (ex-data err)]
          (is (= 429 (:status data)))
          (is (= :reason/rate-limit-ip (:reason data)))
          (is (number? (:retry-after data)))))

      ;; Verify audit event logged for rate limit trip
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/rate-limit-exceeded})]
        (is (= 1 (count events)))
        (is (= "172.16.0.10" (:sec-event/client-ip (first events))))
        (is (= :reason/rate-limit-ip (:sec-event/reason (first events))))))))

;; ============================================================================
;; 7. Identity Authorization Allowlist & Anti-Enumeration (§3, §9.2)
;; ============================================================================

(deftest identity-authorization-allowlist-test
  (testing "Identity authorization gate enforces staff allowlist without leaking membership:
            - Authorized identities receive signed token, XTDB entity, and email
            - Unauthorized identities get identical success UX message, but NO token, NO email,
              and distinct :sec.type/unauthorized-magic-link-requested audit log"
    (try
      (magic-link/reset-authorized-identities!)

      ;; 1. Authorized identity: coach@breakwindow.lan
      (let [auth-res (magic-link/generate-magic-link!
                      *node*
                      {:identity "coach@breakwindow.lan"
                       :secret test-secret
                       :client-ip "10.0.0.1"
                       :send-email? true})]
        (is (true? (:authorized? auth-res)))
        (is (string? (:token auth-res)))
        (is (some? (:magic-link-url auth-res)))
        (is (= "If this email is registered to coaching staff, an access link has been sent."
               (:message auth-res)))

        ;; Verified in XTDB
        (let [db (store-query/db-at *node*)
              doc (store-query/entity db (str "token-" (:jti auth-res)))]
          (is (some? doc))
          (is (= "coach@breakwindow.lan" (:token/identity doc))))

        ;; Email spooled
        (is (= 1 (count (email/get-spooled-emails)))))

      ;; 2. Unauthorized identity: intruder@external.org
      (let [unauth-res (magic-link/generate-magic-link!
                        *node*
                        {:identity "intruder@external.org"
                         :secret test-secret
                         :client-ip "198.51.100.99"
                         :send-email? true})]
        ;; Indistinguishable UX response to prevent user enumeration
        (is (false? (:authorized? unauth-res)))
        (is (nil? (:token unauth-res)))
        (is (nil? (:magic-link-url unauth-res)))
        (is (nil? (:jti unauth-res)))
        (is (= "If this email is registered to coaching staff, an access link has been sent."
               (:message unauth-res)))

        ;; No token document in XTDB for unauthorized email
        (let [db (store-query/db-at *node*)
              tokens (xt/q db '{:find [?t] :where [[?t :token/identity "intruder@external.org"]]})]
          (is (empty? tokens)))

        ;; No email dispatched to unauthorized address (spool count remains 1 from coach above)
        (is (= 1 (count (email/get-spooled-emails))))

        ;; Distinct security audit event logged for unauthorized attempt
        (let [db (store-query/db-at *node*)
              unauth-events (store-query/find-security-events
                             db
                             {:type :sec.type/unauthorized-magic-link-requested})]
          (is (= 1 (count unauth-events)))
          (let [evt (first unauth-events)]
            (is (= "intruder@external.org" (:sec-event/identity evt)))
            (is (= "198.51.100.99" (:sec-event/client-ip evt)))
            (is (= :status/failure (:sec-event/status evt)))
            (is (= :reason/unauthorized-identity (:sec-event/reason evt))))))

      ;; 3. Dynamic authorization: authorize new staff email
      (is (false? (magic-link/authorized-identity? *node* "assistant-analyst@breakwindow.lan")))
      (magic-link/authorize-identity! "assistant-analyst@breakwindow.lan")
      (is (true? (magic-link/authorized-identity? *node* "assistant-analyst@breakwindow.lan")))

      (let [new-staff-res (magic-link/generate-magic-link!
                           *node*
                           {:identity "assistant-analyst@breakwindow.lan"
                            :secret test-secret
                            :send-email? false})]
        (is (true? (:authorized? new-staff-res)))
        (is (string? (:token new-staff-res))))

      (finally
        (magic-link/reset-authorized-identities!)))))

;; ============================================================================
;; 8. Session Cookie Secure Flag (§4, §9.2)
;; ============================================================================

(deftest session-cookie-secure-flag-test
  (testing "Session cookie specification includes Secure flag by default for HTTPS transport"
    (let [cookie-spec (session/build-session-cookie "test-jwt-token")]
      (is (= "test-jwt-token" (:value cookie-spec)))
      (is (= "/" (:path cookie-spec)))
      (is (true? (:http-only cookie-spec)))
      (is (= :lax (:same-site cookie-spec)))
      (is (true? (:secure cookie-spec)) "Cookie MUST have :secure true by default for HTTPS (§4)"))

    (let [clear-spec (session/clear-session-cookie)]
      (is (= "" (:value clear-spec)))
      (is (= 0 (:max-age clear-spec)))
      (is (true? (:secure clear-spec)) "Clear cookie MUST also specify :secure true"))))

;; ============================================================================
;; 9. SMTP Identity & Header Injection Validation (§9.2)
;; ============================================================================

(deftest smtp-header-injection-and-syntax-validation-test
  (testing "Strict email validation rejects CRLF header injection and malformed identities before socket operations"
    ;; 1. CRLF Injection Attempt: attacker@x.com\r\nBcc:victim@company.com
    (let [injection-payload "attacker@breakwindow.lan\r\nBcc:everyone@company.com"]
      (is (false? (email/valid-email? injection-payload)))
      (let [err (is (thrown-with-msg?
                     clojure.lang.ExceptionInfo
                     #"Invalid email address format or header injection characters detected"
                     (magic-link/generate-magic-link!
                      *node*
                      {:identity injection-payload
                       :secret test-secret
                       :client-ip "192.168.1.99"
                       :send-email? true})))]
        (is (= :reason/invalid-email-format (:reason (ex-data err)))))

      ;; Proves rejected at boundary before email spool or socket
      (is (empty? (email/get-spooled-emails)))

      ;; Security event was logged in XTDB for the injection attempt
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/magic-link-rejected})]
        (is (= 1 (count events)))
        (is (= :reason/invalid-email-format (:sec-event/reason (first events))))
        (is (= "192.168.1.99" (:sec-event/client-ip (first events))))))

    ;; 2. URL-encoded CRLF Injection: attacker@breakwindow.lan%0d%0aBcc:...
    (let [encoded-payload "attacker@breakwindow.lan%0d%0aBcc:everyone@company.com"]
      (is (false? (email/valid-email? encoded-payload)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Invalid email address format"
           (email/validate-email! encoded-payload))))

    ;; 3. Malformed syntax
    (is (false? (email/valid-email? "not-an-email")))
    (is (false? (email/valid-email? "")))
    (is (false? (email/valid-email? nil)))
    (is (false? (email/valid-email? "coach@breakwindow")))))


