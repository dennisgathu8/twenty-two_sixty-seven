(ns bwr.web.admin-test
  "Comprehensive tests for bwr.web admin routes and operational controls (§8, §9, §12):
   - Authentication gating returning explicit 401 Unauthorized (not redirect) for missing sessions
   - Anti-forgery CSRF enforcement returning 403 Forbidden for missing or mismatched tokens
   - Hot-reloading declarative rules from resources/rules with fail-safe spec validation
   - Rejecting invalid/malformed rules with 422 Unprocessable Entity leaving active rules intact
   - Mandatory non-blank reason validation on data-quality overrides (400 Bad Request if blank)
   - Happy path data-quality override updating XTDB and immediately reflecting in public GET /matches/:id view
   - Auditable security event logging for all operational actions in XTDB."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [bwr.store.node :as store-node]
            [bwr.store.query :as store-query]
            [bwr.auth.session :as session]
            [bwr.auth.magic-link :as magic-link]
            [bwr.web.routes :as routes]
            [bwr.rules.loader :as rules-loader]))

(def ^:dynamic *node* nil)

(defn with-clean-admin-store [f]
  (let [node (store-node/start-node! {:topology :in-memory})]
    (try
      (binding [*node* node]
        (f))
      (finally
        (store-node/stop-node! node)))))

(use-fixtures :each with-clean-admin-store)

(def fixture-match-unverified
  {:match/id "M42"
   :match/tournament "wc2026"
   :match/home-team "France"
   :match/away-team "Iraq"
   :match/kickoff #inst "2026-06-22T15:00:00Z"
   :match/venue "Philadelphia Stadium"
   :match/data-source :source/fbref
   :match/data-quality :quality/unverified})

(defn- setup-test-environment! [node]
  ;; 1. Ingest test match
  (store-query/transact! node [fixture-match-unverified])
  ;; 2. Ingest built-in rule from resources
  (let [rules (vals (rules-loader/load-rules-from-resources))]
    (doseq [r rules]
      (rules-loader/persist-rule! node (assoc r :xt/id (:rule/id r))))))

(defn- authenticated-request
  "Helper to create a Ring mock request bearing valid session cookie."
  [method uri session-info]
  (let [req (mock/request method uri)]
    (mock/cookie req session/session-cookie-name (:token session-info))))

;; ============================================================================
;; 1. Authentication Gating: 401 Unauthorized for Unauthenticated Requests (§9)
;; ============================================================================

(deftest auth-gating-test
  (testing "All /admin routes reject unauthenticated requests with HTTP 401 (not 302 redirect)"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)]

      ;; 1. GET /admin without session
      (let [resp (app (mock/request :get "/admin"))]
        (is (= 401 (:status resp)))
        (is (str/includes? (:body resp) "Unauthorized"))
        (is (contains? (:headers resp) "Content-Security-Policy")))

      ;; 2. POST /admin/rules/break-window-substitution-pattern/reload without session
      (let [resp (app (mock/request :post "/admin/rules/break-window-substitution-pattern/reload"))]
        (is (= 401 (:status resp)))
        (is (str/includes? (:body resp) "Unauthorized")))

      ;; 3. POST /admin/matches/M42/data-quality without session
      (let [resp (app (mock/request :post "/admin/matches/M42/data-quality"))]
        (is (= 401 (:status resp)))
        (is (str/includes? (:body resp) "Unauthorized")))

      ;; 4. Verify audit event logged in XTDB
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/unauthorized-access-blocked})]
        (is (>= (count events) 3))
        (is (= :reason/unauthenticated (:sec-event/reason (first events))))))))

;; ============================================================================
;; 2. Anti-Forgery CSRF Enforcement: 403 Forbidden (§8, §9)
;; ============================================================================

(deftest csrf-protection-test
  (testing "Admin POST routes reject requests with missing or invalid CSRF tokens with HTTP 403"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)
          sess (session/create-session! *node* "head-coach@breakwindow.lan")
          cookie-token (:token sess)]

      ;; 1. Valid session, but completely missing CSRF token -> 403 Forbidden
      (let [req (-> (mock/request :post "/admin/rules/break-window-substitution-pattern/reload")
                    (mock/cookie session/session-cookie-name cookie-token))
            resp (app req)]
        (is (= 403 (:status resp)))
        (is (str/includes? (:body resp) "Forbidden: Invalid or missing CSRF"))
        (is (contains? (:headers resp) "Content-Security-Policy")))

      ;; 2. Valid session, but wrong/forged CSRF token -> 403 Forbidden
      (let [req (-> (mock/request :post "/admin/rules/break-window-substitution-pattern/reload"
                                  {"__anti-forgery-token" "forged-attacker-csrf-token-xyz"})
                    (mock/cookie session/session-cookie-name cookie-token))
            resp (app req)]
        (is (= 403 (:status resp)))
        (is (str/includes? (:body resp) "Forbidden: Invalid or missing CSRF")))

      ;; 3. Valid session, forged CSRF token via header -> 403 Forbidden
      (let [req (-> (mock/request :post "/admin/matches/M42/data-quality"
                                  {"quality" "verified" "reason" "Valid reason"})
                    (mock/cookie session/session-cookie-name cookie-token)
                    (mock/header "x-csrf-token" "tampered-token-123"))
            resp (app req)]
        (is (= 403 (:status resp)))
        (is (str/includes? (:body resp) "Forbidden")))

      ;; 4. Verify audit event logged in XTDB
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/csrf-rejected})]
        (is (>= (count events) 3))
        (is (= :reason/csrf-token-mismatch (:sec-event/reason (first events))))
        (is (= "head-coach@breakwindow.lan" (:sec-event/identity (first events))))))))

;; ============================================================================
;; 3. Admin Dashboard View (GET /admin)
;; ============================================================================

(deftest admin-dashboard-view-test
  (testing "GET /admin with valid session renders dashboard with strict CSP, rule controls, and CSRF token"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)
          sess (session/create-session! *node* "analyst@breakwindow.lan")
          req (authenticated-request :get "/admin" sess)
          resp (app req)
          body (:body resp)
          headers (:headers resp)
          csp (get headers "Content-Security-Policy")]

      (is (= 200 (:status resp)))
      (is (contains? headers "Content-Security-Policy"))
      (is (str/includes? csp "default-src 'self'"))
      (is (not (str/includes? csp "unsafe-inline")))
      ;; Zero inline style tags or attributes
      (is (not (str/includes? body "style=")))

      ;; Contains operator identity and semantic controls
      (is (str/includes? body "analyst@breakwindow.lan"))
      (is (str/includes? body "Declarative Rule Engine Status"))
      (is (str/includes? body ":rule/break-window-substitution-pattern"))
      (is (str/includes? body "Hot Reload"))
      (is (str/includes? body "Match Data-Quality Override Management"))
      ;; Embeds valid CSRF token in forms
      (is (str/includes? body (:csrf-token sess))))))

;; ============================================================================
;; 4. Mandatory Non-Blank Reason on Data-Quality Overrides (400 Bad Request)
;; ============================================================================

(deftest mandatory-reason-validation-test
  (testing "POST /admin/matches/:id/data-quality rejects blank/missing reason with 400 Bad Request"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)
          sess (session/create-session! *node* "head-coach@breakwindow.lan")
          cookie-token (:token sess)
          csrf (:csrf-token sess)]

      ;; 1. Missing reason
      (let [req (-> (mock/request :post "/admin/matches/M42/data-quality"
                                  {"quality" "verified"
                                   "__anti-forgery-token" csrf})
                    (mock/cookie session/session-cookie-name cookie-token))
            resp (app req)]
        (is (= 400 (:status resp)))
        (is (str/includes? (:body resp) "mandatory non-blank audit reason")))

      ;; 2. Empty string reason
      (let [req (-> (mock/request :post "/admin/matches/M42/data-quality"
                                  {"quality" "verified"
                                   "reason" ""
                                   "__anti-forgery-token" csrf})
                    (mock/cookie session/session-cookie-name cookie-token))
            resp (app req)]
        (is (= 400 (:status resp)))
        (is (str/includes? (:body resp) "mandatory non-blank audit reason")))

      ;; 3. Whitespace-only reason
      (let [req (-> (mock/request :post "/admin/matches/M42/data-quality"
                                  {"quality" "verified"
                                   "reason" "     "
                                   "__anti-forgery-token" csrf})
                    (mock/cookie session/session-cookie-name cookie-token))
            resp (app req)]
        (is (= 400 (:status resp)))
        (is (str/includes? (:body resp) "mandatory non-blank audit reason")))

      ;; 4. Verify match in XTDB was NOT updated
      (let [db (store-query/db-at *node*)
            m (store-query/find-match db "M42")]
        (is (= :quality/unverified (:match/data-quality m))))

      ;; 5. Verify NO override event was logged
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/data-quality-overridden})]
        (is (empty? events))))))

;; ============================================================================
;; 5. Fail-Safe Rule Hot-Reload Rejection (422 Unprocessable Entity)
;; ============================================================================

(deftest fail-safe-rule-reload-test
  (testing "Hot-reload rejects malformed rule with 422 Unprocessable Entity leaving active rule untouched"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)
          sess (session/create-session! *node* "head-coach@breakwindow.lan")
          cookie-token (:token sess)
          csrf (:csrf-token sess)
          original-rule (rules-loader/fetch-rule (store-query/db-at *node*) :rule/break-window-substitution-pattern)]

      (is (some? original-rule))

      ;; 1. Nonexistent rule ID -> 404 Not Found
      (let [req (-> (mock/request :post "/admin/rules/nonexistent-rule/reload"
                                  {"__anti-forgery-token" csrf})
                    (mock/cookie session/session-cookie-name cookie-token))
            resp (app req)]
        (is (= 404 (:status resp)))
        (is (str/includes? (:body resp) "Rule definition file not found")))

      ;; 2. Malformed rule file scenario:
      ;; We temporarily create a malformed rule in resources/rules to test fail-safe loading
      (let [bad-rule-file (clojure.java.io/file "resources/rules/malformed_test_rule.edn")]
        (try
          ;; Invalid schema: missing :rule/predicate and :rule/window-s
          (spit bad-rule-file "{:rule/id :rule/malformed-test-rule :rule/version \"0.0.1\"}" :encoding "UTF-8")

          (let [req (-> (mock/request :post "/admin/rules/malformed-test-rule/reload"
                                      {"__anti-forgery-token" csrf})
                        (mock/cookie session/session-cookie-name cookie-token))
                resp (app req)]
            ;; 422 Unprocessable Entity
            (is (= 422 (:status resp)))
            (is (str/includes? (:body resp) "spec validation error"))

            ;; Verify audit failure event logged
            (let [db (store-query/db-at *node*)
                  events (store-query/find-security-events db {:type :sec.type/rule-reload-failed})]
              (is (pos? (count events)))
              (is (= :reason/spec-validation-failed (:sec-event/reason (first events))))))

          (finally
            (when (.exists bad-rule-file)
              (.delete bad-rule-file))))))

    ;; 3. Confirm active rule in XTDB remains completely intact
    (let [current-rule (rules-loader/fetch-rule (store-query/db-at *node*) :rule/break-window-substitution-pattern)]
      (is (some? current-rule))
      (is (= "1.0.0" (:rule/version current-rule))))))

;; ============================================================================
;; 6. Happy Path Rule Hot-Reload (§7, §8)
;; ============================================================================

(deftest rule-hot-reload-happy-path-test
  (testing "Authenticated coach hot-reloads valid rule updating timestamp and logging audit event"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)
          sess (session/create-session! *node* "head-coach@breakwindow.lan")
          cookie-token (:token sess)
          csrf (:csrf-token sess)
          req (-> (mock/request :post "/admin/rules/break-window-substitution-pattern/reload"
                                {"__anti-forgery-token" csrf})
                  (mock/cookie session/session-cookie-name cookie-token))
          resp (app req)]

      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "hot-reloaded successfully"))

      ;; Verify XTDB record updated with :rule/reloaded-at
      (let [db (store-query/db-at *node*)
            rule (rules-loader/fetch-rule db :rule/break-window-substitution-pattern)]
        (is (some? rule))
        (is (some? (:rule/reloaded-at rule)))
        (is (instance? java.util.Date (:rule/reloaded-at rule))))

      ;; Verify security audit event logged
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/rule-reloaded})]
        (is (= 1 (count events)))
        (let [ev (first events)]
          (is (= "head-coach@breakwindow.lan" (:sec-event/identity ev)))
          (is (= :status/success (:sec-event/status ev)))
          (is (= :rule/break-window-substitution-pattern (get-in ev [:sec-event/detail :rule-id]))))))))

;; ============================================================================
;; 7. Happy Path Data-Quality Override & Public Reflection (§8, §9)
;; ============================================================================

(deftest data-quality-override-and-public-reflection-test
  (testing "Data-quality override updates match in XTDB and immediately reflects in GET /matches/:id view"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)
          sess (session/create-session! *node* "head-coach@breakwindow.lan")
          cookie-token (:token sess)
          csrf (:csrf-token sess)]

      ;; 1. Check initial public match view: badge should be "Unverified"
      (let [initial-resp (app (mock/request :get "/matches/M42"))]
        (is (= 200 (:status initial-resp)))
        (is (str/includes? (:body initial-resp) "badge-unverified"))
        (is (str/includes? (:body initial-resp) "Unverified Data"))
        (is (not (str/includes? (:body initial-resp) "badge-verified"))))

      ;; 2. Admin submits data-quality override to "verified" with required audit reason
      (let [override-req (-> (mock/request :post "/admin/matches/M42/data-quality"
                                           {"quality" "verified"
                                            "reason" "Audited against official FIFA match commissioner match sheet"
                                            "__anti-forgery-token" csrf})
                             (mock/cookie session/session-cookie-name cookie-token))
            override-resp (app override-req)]
        (is (= 200 (:status override-resp)))
        (is (str/includes? (:body override-resp) "data quality overridden to verified")))

      ;; 3. Verify XTDB match entity is updated
      (let [db (store-query/db-at *node*)
            m (store-query/find-match db "M42")]
        (is (= :quality/verified (:match/data-quality m))))

      ;; 4. Verify security audit event in XTDB
      (let [db (store-query/db-at *node*)
            events (store-query/find-security-events db {:type :sec.type/data-quality-overridden})]
        (is (= 1 (count events)))
        (let [ev (first events)]
          (is (= "head-coach@breakwindow.lan" (:sec-event/identity ev)))
          (is (= "M42" (get-in ev [:sec-event/detail :match-id])))
          (is (= :quality/unverified (get-in ev [:sec-event/detail :old-quality])))
          (is (= :quality/verified (get-in ev [:sec-event/detail :new-quality])))
          (is (= "Audited against official FIFA match commissioner match sheet"
                 (get-in ev [:sec-event/detail :reason])))))

      ;; 5. CRITICAL PROOF: Verify public match view immediately reflects "Verified Data" badge
      (let [updated-resp (app (mock/request :get "/matches/M42"))]
        (is (= 200 (:status updated-resp)))
        (is (str/includes? (:body updated-resp) "badge-verified"))
        (is (str/includes? (:body updated-resp) "Verified Data"))
        (is (not (str/includes? (:body updated-resp) "badge-unverified")))))))

;; ============================================================================
;; 8. Single-Use Magic Link Verification & Set-Cookie Emission (§9.2, §10)
;; ============================================================================

(deftest auth-verify-endpoint-and-set-cookie-test
  (testing "GET /auth/verify renders confirmation page without consuming token"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)
          gen-res (magic-link/generate-magic-link! *node* {:identity "head-coach@breakwindow.lan"
                                                           :client-ip "127.0.0.1"
                                                           :send-email? false})
          token (:token gen-res)
          jti (:jti gen-res)]

      ;; 1. GET with missing token -> 400
      (let [resp (app (mock/request :get "/auth/verify"))]
        (is (= 400 (:status resp)))
        (is (str/includes? (:body resp) "Missing required magic link authentication token")))

      ;; 2. GET with malformed token -> 400
      (let [resp (app (mock/request :get "/auth/verify?token=short"))]
        (is (= 400 (:status resp)))
        (is (str/includes? (:body resp) "Malformed magic link authentication token format")))

      ;; 3. GET with valid token -> 200 Confirm Sign-in page
      (let [resp (app (mock/request :get (str "/auth/verify?token=" token)))]
        (is (= 200 (:status resp)))
        (is (str/includes? (:body resp) "Confirm Sign-in"))
        (is (str/includes? (:body resp) "action=\"/auth/verify\""))
        (is (str/includes? (:body resp) token)))

      ;; 4. Verify token was NOT consumed by GET
      (let [db (store-query/db-at *node*)
            token-doc (store-query/entity db (str "token-" jti))]
        (is (some? token-doc))
        (is (false? (:token/consumed? token-doc))))))

  (testing "POST /auth/verify atomically consumes token, redirects 303 to /admin, and sets secure cookie"
    (setup-test-environment! *node*)
    (let [app (routes/create-app *node*)
          gen-res (magic-link/generate-magic-link! *node* {:identity "head-coach@breakwindow.lan"
                                                           :client-ip "127.0.0.1"
                                                           :send-email? false})
          token (:token gen-res)
          jti (:jti gen-res)
          post-resp (app (mock/request :post "/auth/verify" {"token" token}))]

      ;; 1. Status is 303 See Other
      (is (= 303 (:status post-resp)))
      (is (= "/admin" (get-in post-resp [:headers "Location"])))

      ;; 2. Set-Cookie header emitted with required security flags (§9.2, §4)
      (let [set-cookie (or (get-in post-resp [:headers "Set-Cookie"])
                           (get-in post-resp [:headers "set-cookie"]))
            cookie-str (if (coll? set-cookie) (first set-cookie) (str set-cookie))]
        (is (some? set-cookie) "Set-Cookie header must be emitted on verify response")
        (is (str/includes? cookie-str (str session/session-cookie-name "=")))
        (is (str/includes? cookie-str "HttpOnly"))
        (is (str/includes? cookie-str "SameSite=Lax"))
        (is (str/includes? cookie-str "Path=/"))
        ;; When not in BWR_ENV=test mode, Secure flag must be present (§9.2, §4)
        (is (str/includes? cookie-str "Secure")))

      ;; 3. Token is now marked consumed in XTDB
      (let [db (store-query/db-at *node*)
            token-doc (store-query/entity db (str "token-" jti))]
        (is (some? token-doc))
        (is (true? (:token/consumed? token-doc))))

      ;; 4. Replay attack: submitting same token again returns 401
      (let [replay-resp (app (mock/request :post "/auth/verify" {"token" token}))]
        (is (= 401 (:status replay-resp)))
        (is (str/includes? (:body replay-resp) "Authentication Failed")))))

  (testing "POST /auth/verify in test mode relaxes Secure flag for local HTTP testing"
    (setup-test-environment! *node*)
    (with-redefs [session/test-environment? (constantly true)]
      (let [app (routes/create-app *node*)
            gen-res (magic-link/generate-magic-link! *node* {:identity "head-coach@breakwindow.lan"
                                                             :client-ip "127.0.0.1"
                                                             :send-email? false})
            token (:token gen-res)
            post-resp (app (mock/request :post "/auth/verify" {"token" token}))
            set-cookie (or (get-in post-resp [:headers "Set-Cookie"])
                           (get-in post-resp [:headers "set-cookie"]))
            cookie-str (if (coll? set-cookie) (first set-cookie) (str set-cookie))]
        (is (= 303 (:status post-resp)))
        (is (some? set-cookie))
        (is (str/includes? cookie-str "HttpOnly"))
        (is (not (str/includes? cookie-str "Secure")))))))

