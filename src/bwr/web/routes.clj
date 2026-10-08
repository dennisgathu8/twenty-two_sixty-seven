(ns bwr.web.routes
  "Reitit route definitions and Ring request handlers for public SSR views (§8)
   and authenticated admin operations (§9). Enforces boundary validation on path
   parameters (§9.1) and provides explicit 404/400/401/403/422 handling."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as params]
            [ring.middleware.cookies :as cookies]
            [ring.util.response :as resp]
            [clojure.data.json :as json]
            [bwr.store.query :as store-query]
            [bwr.security.middleware :as sec]
            [bwr.auth.magic-link :as magic-link]
            [bwr.auth.session :as session]
            [bwr.rules.loader :as rules-loader]
            [bwr.web.views.layout :as layout]
            [bwr.web.views.matches :as matches]
            [bwr.web.views.teams :as teams]
            [bwr.web.views.admin :as admin]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Boundary Parameter Validation Specs (§9.1)
;; ============================================================================

(s/def ::match-id
  (s/and string?
         #(re-matches #"^[a-zA-Z0-9_\-]+$" %)
         #(<= 1 (count %) 64)))

(s/def ::stoppage-id
  (s/and string?
         #(re-matches #"^[a-zA-Z0-9_\-]+$" %)
         #(<= 1 (count %) 64)))

(s/def ::team-id
  (s/and string?
         #(re-matches #"^[a-zA-Z0-9_\- ]+$" %)
         #(<= 1 (count %) 64)))

(s/def ::rule-id
  (s/and string?
         #(re-matches #"^[a-zA-Z0-9_\-]+$" %)
         #(<= 1 (count %) 64)))

(s/def ::magic-token
  (s/and string?
         #(re-matches #"^[a-zA-Z0-9_\-\.]+$" %)
         #(<= 10 (count %) 1024)))

(defn- decode-param
  "Decodes a URL-encoded string safely."
  [^String s]
  (try
    (java.net.URLDecoder/decode s "UTF-8")
    (catch Exception _ s)))

;; ============================================================================
;; View Handlers
;; ============================================================================

(defn home-handler
  "Renders the public homepage and match index."
  [node _request]
  (let [db (store-query/db-at node)
        all-matches (store-query/find-all-matches db)]
    (layout/html-response
     (layout/base-layout
      {:title "Break-Window Response — FIFA 2026 Decision Support"
       :description "Decision support and auditable rule evaluation for mandatory hydration breaks (22' & 67')."}

      [:div.card
       [:h1 "Break-Window Response"]
       [:p.hero-lead
        "Decision support for FIFA 2026 mandatory hydration breaks (22' and 67'). "
        "Auditable, inspectable rules powered by core.logic over bi-temporal XTDB storage."]
       [:div.match-meta
        [:span [:strong "Architecture: "] "Parens to Production (SSR / Hiccup / Reitit)"]
        [:span [:strong "Storage: "] "XTDB 1.x (ADR-001)"]
        [:span [:strong "Rules: "] "core.logic data-driven relations (§7)"]]]

      [:div.card
       [:h2.section-title "Ingested Match Reports"]
       (if (empty? all-matches)
         [:p "No matches currently ingested in storage. Ingest match reports to view break-window analyses."]
         [:table.data-table
          [:thead
           [:tr
            [:th "Match ID"]
            [:th "Tournament"]
            [:th "Fixture"]
            [:th "Venue"]
            [:th "Quality"]
            [:th "Analysis"]]]
          [:tbody
           (for [m all-matches
                 :let [mid (:match/id m)
                       home (:match/home-team m)
                       away (:match/away-team m)
                       verified? (= :quality/verified (:match/data-quality m))]]
             [:tr {:key mid}
              [:td [:span.code-id mid]]
              [:td (:match/tournament m)]
              [:td (str home " vs " away)]
              [:td (:match/venue m)]
              [:td (if verified?
                     [:span.badge.badge-verified "Verified"]
                     [:span.badge.badge-unverified "Unverified"])]
              [:td [:a {:href (str "/matches/" mid)} "View Match Report →"]]])]])]))))

(defn match-handler
  "Renders the full match report (GET /matches/:id).
   Returns 400 for malformed IDs, 404 for unknown matches."
  [node request]
  (let [raw-id (get-in request [:path-params :id])
        match-id (decode-param (or raw-id ""))]
    (if-not (s/valid? ::match-id match-id)
      (layout/error-response 400 "Bad Request"
                             "Invalid match identifier. Match ID must be 1-64 alphanumeric characters, dashes, or underscores.")
      (let [db (store-query/db-at node)
            match (store-query/find-match db match-id)]
        (if-not match
          (layout/error-response 404 "Match Not Found"
                                 (str "No match found with identifier '" match-id "' in verified storage."))
          (let [stoppages (store-query/find-stoppages-by-match db match-id)
                events (store-query/find-events-by-match db match-id)
                recommendations (store-query/find-recommendations-for-match db match-id)]
            (layout/html-response
             (matches/match-report-view
              {:match match
               :stoppages stoppages
               :events events
               :recommendations recommendations}))))))))

(defn stoppage-handler
  "Renders the stoppage detail with evidence drilldown (GET /matches/:id/stoppages/:sid).
   Returns 400 for malformed IDs, 404 for unknown matches or non-matching stoppages."
  [node request]
  (let [raw-mid (get-in request [:path-params :id])
        raw-sid (get-in request [:path-params :sid])
        match-id (decode-param (or raw-mid ""))
        stoppage-id (decode-param (or raw-sid ""))]
    (cond
      (not (s/valid? ::match-id match-id))
      (layout/error-response 400 "Bad Request" "Invalid match identifier.")

      (not (s/valid? ::stoppage-id stoppage-id))
      (layout/error-response 400 "Bad Request" "Invalid stoppage identifier.")

      :else
      (let [db (store-query/db-at node)
            match (store-query/find-match db match-id)]
        (if-not match
          (layout/error-response 404 "Match Not Found"
                                 (str "Match '" match-id "' not found."))
          (let [stoppage (store-query/entity db stoppage-id)]
            (if (or (not stoppage) (not= (:stoppage/match stoppage) match-id))
              (layout/error-response 404 "Stoppage Not Found"
                                     (str "Stoppage '" stoppage-id "' not found for match '" match-id "'."))
              (let [all-recs (store-query/find-recommendations-for-match db match-id)
                    recs-for-stoppage (filterv #(= (:recommendation/stoppage %) stoppage-id) all-recs)
                    evidence-ids (distinct (mapcat :recommendation/evidence recs-for-stoppage))
                    evidence-entities (keep #(store-query/entity db %) evidence-ids)]
                (layout/html-response
                 (matches/stoppage-detail-view
                  {:match match
                   :stoppage stoppage
                   :recommendations recs-for-stoppage
                   :evidence-entities evidence-entities}))))))))))

(defn team-handler
  "Renders aggregated team-level break-window tendencies (GET /teams/:id/break-profile).
   Returns 400 for malformed team IDs, 404 when no records exist for the team."
  [node request]
  (let [raw-id (get-in request [:path-params :id])
        team-name (decode-param (or raw-id ""))]
    (if-not (s/valid? ::team-id team-name)
      (layout/error-response 400 "Bad Request" "Invalid team identifier.")
      (let [db (store-query/db-at node)
            recs (store-query/find-recommendations-for-team db team-name)
            all-matches (store-query/find-all-matches db)
            matches-for-team (filterv (fn [m]
                                        (or (= (:match/home-team m) team-name)
                                            (= (:match/away-team m) team-name)))
                                      all-matches)]
        (if (and (empty? recs) (empty? matches-for-team))
          (layout/error-response 404 "Team Not Found"
                                 (str "No match records or break-window tendencies recorded for team '" team-name "'."))
          (layout/html-response
           (teams/team-break-profile-view
            {:team team-name
             :recommendations recs
             :matches matches-for-team})))))))

(defn static-css-handler
  "Serves static CSS assets from resources/public/css with security headers."
  [request]
  (let [path (get-in request [:path-params :path])
        resource-path (str "public/css/" path)]
    (if-let [res (io/resource resource-path)]
      (-> (resp/response (slurp res :encoding "UTF-8"))
          (resp/content-type "text/css; charset=utf-8")
          (update :headers merge layout/security-headers))
      (layout/error-response 404 "Asset Not Found" (str "Asset '" path "' was not found.")))))

(defn auth-verify-get-handler
  "Validates token format and renders a 'Confirm sign-in' form without consuming (§9.2).
   Protects single-use tokens from accidental burning by automated email scanners."
  [_node request]
  (let [raw-token (or (get-in request [:params :token])
                      (get-in request [:params "token"]))
        token (when raw-token (str/trim (str raw-token)))]
    (cond
      (or (nil? token) (str/blank? token))
      (layout/error-response 400 "Bad Request" "Missing required magic link authentication token.")

      (not (s/valid? ::magic-token token))
      (layout/error-response 400 "Bad Request" "Malformed magic link authentication token format.")

      :else
      (layout/html-response
       (layout/base-layout
        {:title "Confirm Sign-in — Break-Window Response"
         :description "Confirm single-use sign-in to administrative dashboard."}
        [:div.card
         [:h1 "Confirm Sign-in"]
         [:p "Click below to confirm your single-use sign-in to the Break-Window Response coaching staff portal."]
         [:form#confirm-signin-form {:method "POST" :action "/auth/verify"}
          [:input {:type "hidden" :name "token" :value token}]
          [:button#confirm-signin-btn.btn.btn-primary {:type "submit"} "Confirm sign-in"]]])))))

(defn auth-verify-post-handler
  "Atomically consumes the single-use magic link token (§9.2).
   On success: sets authenticated bwr_session cookie and redirects 303 to /admin.
   On failure/tampering/replay: returns semantic 401 error page."
  [node request]
  (let [raw-token (or (get-in request [:params :token])
                      (get-in request [:params "token"])
                      (get-in request [:form-params "token"])
                      (get-in request [:form-params :token]))
        token (when raw-token (str/trim (str raw-token)))]
    (cond
      (or (nil? token) (str/blank? token))
      (layout/error-response 400 "Bad Request" "Missing required magic link authentication token.")

      (not (s/valid? ::magic-token token))
      (layout/error-response 400 "Bad Request" "Malformed magic link authentication token format.")

      :else
      (let [client-ip (or (:bwr/client-ip request) (:remote-addr request) "unknown")
            res (session/verify-magic-link-and-create-session! node token {:client-ip client-ip})]
        (if (:valid? res)
          (let [session-token (:session-token res)
                cookie-spec (session/build-session-cookie session-token)]
            (-> (resp/redirect "/admin" :see-other)
                (resp/set-cookie session/session-cookie-name session-token cookie-spec)))
          (layout/error-response 401 "Authentication Failed"
                                 (or (:error res) "Magic link is invalid, expired, or already consumed.")))))))

(def auth-verify-handler
  "Backwards-compatible handler executing POST verification."
  auth-verify-post-handler)

(defn test-magic-link-handler
  "Test-only magic link minter, strictly mounted when BWR_ENV=test.
   Enables automated browser E2E tests to drive real magic-link verification."
  [node request]
  (if-not (session/test-environment?)
    (layout/error-response 404 "Not Found" "The requested URL was not found on this server.")
    (let [raw-id (or (get-in request [:params :identity])
                     (get-in request [:params "identity"])
                     "head-coach@breakwindow.lan")
          client-ip (or (:bwr/client-ip request) (:remote-addr request) "unknown")
          res (magic-link/generate-magic-link! node {:identity raw-id
                                                     :client-ip client-ip
                                                     :send-email? false})]
      (if (:authorized? res)
        (-> (resp/response (json/write-str {:status "ok"
                                            :token (:token res)
                                            :magicLinkUrl (str "/auth/verify?token=" (:token res))}))
            (resp/content-type "application/json; charset=utf-8")
            (update :headers merge layout/security-headers))
        (layout/error-response 403 "Forbidden" "Identity not authorized for magic link generation.")))))

;; ============================================================================
;; Admin Operational Handlers (§8, §9)
;; ============================================================================

(defn admin-dashboard-handler
  "Renders the admin dashboard with rule status, hot-reload forms,
   and data-quality override management."
  [node request]
  (let [db (store-query/db-at node)
        store-rules (store-query/find-all-rules db)
        resource-rules (vals (rules-loader/load-rules-from-resources))
        rule-map (into (into {} (map (juxt :rule/id identity) resource-rules))
                       (map (juxt :rule/id identity) store-rules))
        rules (vec (vals rule-map))
        matches (store-query/find-all-matches db)
        csrf (or (:anti-forgery-token request)
                 (get-in request [:session :session/csrf-token]))
        status-msg (or (get-in request [:params :status]) (get-in request [:params "status"]))
        error-msg (or (get-in request [:params :error]) (get-in request [:params "error"]))]
    (layout/html-response
     (admin/admin-dashboard-view
      {:identity (:identity request)
       :rules rules
       :matches matches
       :status-msg status-msg
       :error-msg error-msg
       :anti-forgery-token csrf}))))

(defn- find-rule-content
  "Finds and returns raw EDN string for the given rule identifier, or nil if not found."
  [rule-id-str]
  (let [clean-id (str/replace rule-id-str #"^rule/" "")
        candidates [(str "rules/" clean-id ".edn")
                    (str "rules/" (str/replace clean-id #"-" "_") ".edn")
                    (str "rules/" (str/replace clean-id #"_" "-") ".edn")
                    (str "resources/rules/" clean-id ".edn")
                    (str "resources/rules/" (str/replace clean-id #"-" "_") ".edn")
                    (str "resources/rules/" (str/replace clean-id #"_" "-") ".edn")]]
    (or (some (fn [p]
                (if-let [res (io/resource p)]
                  (slurp res :encoding "UTF-8")
                  (let [f (io/file p)]
                    (when (.exists f)
                      (slurp f :encoding "UTF-8")))))
              candidates)
        ;; Fallback: scan resources/rules directory for matching rule-id
        (let [dir (io/file "resources/rules")]
          (when (and (.exists dir) (.isDirectory dir))
            (some (fn [^java.io.File f]
                    (when (.endsWith (.getName f) ".edn")
                      (try
                        (let [data (clojure.edn/read-string (slurp f :encoding "UTF-8"))
                              rid (name (:rule/id data))]
                          (when (= rid clean-id)
                            (slurp f :encoding "UTF-8")))
                        (catch Exception _ nil))))
                  (.listFiles dir)))))))

(defn admin-rule-reload-handler
  "Hot-reloads a declarative rule from resources/rules/*.edn with fail-safe spec validation (§7, §8).
   Rejects invalid/malformed rules with 422 Unprocessable Entity without touching XTDB."
  [node request]
  (let [raw-id (or (get-in request [:path-params :id])
                   (get-in request [:params :rule_id])
                   (get-in request [:params "rule_id"]))
        rule-id-str (decode-param (or raw-id ""))]
    (cond
      (not (s/valid? ::rule-id rule-id-str))
      (layout/error-response 400 "Bad Request" "Invalid rule identifier.")

      :else
      (if-let [edn-str (find-rule-content rule-id-str)]
        (try
          (let [parsed (rules-loader/parse-rule edn-str)
                now (java.util.Date.)
                rule-doc (assoc parsed
                                :xt/id (:rule/id parsed)
                                :rule/reloaded-at now)
                tx (rules-loader/persist-rule! node rule-doc)]
            (store-query/log-security-event!
             node
             {:sec-event/type :sec.type/rule-reloaded
              :sec-event/identity (:identity request)
              :sec-event/client-ip (or (:bwr/client-ip request) (:remote-addr request) "unknown")
              :sec-event/status :status/success
              :sec-event/reason :reason/admin-action
              :sec-event/detail {:rule-id (:rule/id rule-doc)
                                 :version (:rule/version rule-doc)
                                 :reloaded-at now
                                 :tx-id (first tx)}})
            (let [accept (get-in request [:headers "accept"] "")]
              (if (or (str/includes? accept "application/edn")
                      (str/includes? accept "application/json"))
                (-> (resp/response (pr-str {:status :ok
                                            :rule/id (:rule/id rule-doc)
                                            :rule/version (:rule/version rule-doc)
                                            :rule/reloaded-at now}))
                    (resp/content-type "application/edn; charset=utf-8")
                    (update :headers merge layout/security-headers))
                (layout/html-response
                 (admin/admin-dashboard-view
                  {:identity (:identity request)
                   :rules (store-query/find-all-rules (store-query/db-at node))
                   :matches (store-query/find-all-matches (store-query/db-at node))
                   :status-msg (str "Rule " (:rule/id rule-doc) " (version " (:rule/version rule-doc) ") hot-reloaded successfully at " now ".")
                   :anti-forgery-token (or (:anti-forgery-token request)
                                           (get-in request [:session :session/csrf-token]))})))))
          (catch Throwable t
            ;; Fail-safe: spec validation failed! Active rule in XTDB remains untouched.
            (store-query/log-security-event!
             node
             {:sec-event/type :sec.type/rule-reload-failed
              :sec-event/identity (:identity request)
              :sec-event/client-ip (or (:bwr/client-ip request) (:remote-addr request) "unknown")
              :sec-event/status :status/failure
              :sec-event/reason :reason/spec-validation-failed
              :sec-event/detail {:rule-id rule-id-str
                                 :error (.getMessage t)}})
            (layout/error-response 422 "Unprocessable Entity"
                                   (str "Rule hot-reload failed: spec validation error: " (.getMessage t)))))

        ;; File not found
        (do
          (store-query/log-security-event!
           node
           {:sec-event/type :sec.type/rule-reload-failed
            :sec-event/identity (:identity request)
            :sec-event/client-ip (or (:bwr/client-ip request) (:remote-addr request) "unknown")
            :sec-event/status :status/failure
            :sec-event/reason :reason/file-not-found
            :sec-event/detail {:rule-id rule-id-str}})
          (layout/error-response 404 "Rule Not Found"
                                 (str "Rule definition file not found for '" rule-id-str "'.")))))))

(defn- parse-quality-param
  "Converts a quality string or keyword to a valid :quality/* keyword."
  [q]
  (cond
    (nil? q) :quality/verified
    (keyword? q) (if (= (namespace q) "quality") q (keyword "quality" (name q)))
    (string? q) (let [clean (-> q (str/replace #"^:?quality/" "") (str/replace #"^:" ""))]
                  (keyword "quality" clean))
    :else :quality/verified))

(defn admin-data-quality-handler
  "Overrides the data quality tier for a match (§8, §9).
   Mandates a non-blank audit reason and logs the override event to XTDB."
  [node request]
  (let [raw-id (or (get-in request [:path-params :id])
                   (get-in request [:params :match_id])
                   (get-in request [:params "match_id"]))
        match-id (decode-param (or raw-id ""))
        raw-reason (or (get-in request [:params :reason])
                       (get-in request [:params "reason"]))
        reason (when raw-reason (str/trim (str raw-reason)))
        raw-quality (or (get-in request [:params :quality])
                        (get-in request [:params "quality"])
                        "verified")
        quality (parse-quality-param raw-quality)]
    (cond
      (not (s/valid? ::match-id match-id))
      (layout/error-response 400 "Bad Request" "Invalid match identifier.")

      ;; Mandatory non-blank audit reason (§9.1)
      (or (nil? reason) (str/blank? reason))
      (layout/error-response 400 "Bad Request"
                             "Data quality override requires a mandatory non-blank audit reason.")

      :else
      (let [db (store-query/db-at node)
            match (store-query/find-match db match-id)]
        (if-not match
          (layout/error-response 404 "Match Not Found"
                                 (str "Match '" match-id "' not found in verified storage."))
          (let [old-quality (:match/data-quality match)
                updated-match (assoc match :match/data-quality quality)
                tx (store-query/transact! node [updated-match])]
            (store-query/log-security-event!
             node
             {:sec-event/type :sec.type/data-quality-overridden
              :sec-event/identity (:identity request)
              :sec-event/client-ip (or (:bwr/client-ip request) (:remote-addr request) "unknown")
              :sec-event/status :status/success
              :sec-event/reason :reason/manual-override
              :sec-event/detail {:match-id match-id
                                 :old-quality old-quality
                                 :new-quality quality
                                 :reason reason
                                 :tx-id (first tx)}})
            (let [accept (get-in request [:headers "accept"] "")]
              (if (or (str/includes? accept "application/edn")
                      (str/includes? accept "application/json"))
                (-> (resp/response (pr-str {:status :ok
                                            :match-id match-id
                                            :old-quality old-quality
                                            :new-quality quality
                                            :reason reason}))
                    (resp/content-type "application/edn; charset=utf-8")
                    (update :headers merge layout/security-headers))
                (layout/html-response
                 (admin/admin-dashboard-view
                  {:identity (:identity request)
                   :rules (store-query/find-all-rules (store-query/db-at node))
                   :matches (store-query/find-all-matches (store-query/db-at node))
                   :status-msg (str "Match " match-id " data quality overridden to " (name quality) ". Reason recorded to audit trail.")
                   :anti-forgery-token (or (:anti-forgery-token request)
                                           (get-in request [:session :session/csrf-token]))}))))))))))

;; ============================================================================
;; Route Table & Ring Handler Setup
;; ============================================================================

(defn create-routes
  "Returns the Reitit route table with handlers bound to the given XTDB node."
  [node]
  (let [base-routes
        [["/" {:get (partial home-handler node)}]
         ["/health" {:get (fn [_]
                            (-> (resp/response "{:status :ok :service :break-window-response}")
                                (resp/content-type "application/edn")
                                (update :headers merge layout/security-headers)))}]
         ["/css/*path" {:get static-css-handler}]
         ["/auth/verify" {:get (partial auth-verify-get-handler node)
                          :post (partial auth-verify-post-handler node)}]
         ["/matches/:id"
          ["" {:get (partial match-handler node)}]
          ["/stoppages/:sid" {:get (partial stoppage-handler node)}]]
         ["/teams/:id/break-profile" {:get (partial team-handler node)}]
         ["/admin"
          {:middleware [(session/wrap-require-auth node)
                        (session/wrap-bwr-anti-forgery node)]
           :bwr/require-auth? true}
          ["" {:get (partial admin-dashboard-handler node)}]
          ["/rules/:id/reload" {:post (partial admin-rule-reload-handler node)}]
          ["/matches/:id/data-quality" {:post (partial admin-data-quality-handler node)}]
          ["/matches/override" {:post (partial admin-data-quality-handler node)}]]]]
    (if (session/test-environment?)
      (conj base-routes
            ["/test/auth/magic-link" {:post (partial test-magic-link-handler node)}])
      base-routes)))

(defn create-app
  "Creates and returns the Ring application handler for public SSR views and admin routes.
   Wraps with edge client-IP resolution, security headers, IP containment, rate-limiting,
   session cookies, and parameter parsing."
  ([node] (create-app node {}))
  ([node opts]
   (let [rate-limiter (or (:rate-limiter opts)
                          (sec/create-rate-limiter {:max-requests 100 :window-seconds 60}))
         trusted-proxies (or (:trusted-proxies opts) #{})
         router (ring/router (create-routes node)
                             {:data {:middleware [params/parameters-middleware]}})
         app (ring/ring-handler
              router
              (ring/create-default-handler
               {:not-found (fn [_]
                             (layout/error-response 404 "Not Found" "The requested URL was not found on this server."))
                :method-not-allowed (fn [_]
                                      (layout/error-response 405 "Method Not Allowed" "HTTP method not supported for this route."))}))]
     (-> app
         cookies/wrap-cookies
         (sec/wrap-rate-limit rate-limiter node)
         (sec/wrap-ip-containment node)
         sec/wrap-security-headers
         (sec/wrap-client-ip trusted-proxies)))))
