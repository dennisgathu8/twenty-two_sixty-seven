(ns bwr.web.routes
  "Reitit route definitions and Ring request handlers for public SSR views (§8).
   Enforces boundary validation on path parameters (§9.1) and provides explicit
   404/400 handling without leaking rule logic into the view layer."
  (:require [clojure.spec.alpha :as s]
            [clojure.java.io :as io]
            [reitit.ring :as ring]
            [reitit.ring.middleware.parameters :as params]
            [ring.util.response :as resp]
            [bwr.store.query :as store-query]
            [bwr.web.views.layout :as layout]
            [bwr.web.views.matches :as matches]
            [bwr.web.views.teams :as teams]))

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
      (-> (resp/response (slurp res))
          (resp/content-type "text/css; charset=utf-8")
          (update :headers merge layout/security-headers))
      (layout/error-response 404 "Asset Not Found" (str "Asset '" path "' was not found.")))))

;; ============================================================================
;; Route Table & Ring Handler Setup
;; ============================================================================

(defn create-routes
  "Returns the Reitit route table with handlers bound to the given XTDB node."
  [node]
  [["/" {:get (partial home-handler node)}]
   ["/health" {:get (fn [_]
                      (-> (resp/response "{:status :ok :service :break-window-response}")
                          (resp/content-type "application/edn")
                          (update :headers merge layout/security-headers)))}]
   ["/css/*path" {:get static-css-handler}]
   ["/matches/:id"
    ["" {:get (partial match-handler node)}]
    ["/stoppages/:sid" {:get (partial stoppage-handler node)}]]
   ["/teams/:id/break-profile" {:get (partial team-handler node)}]])

(defn create-app
  "Creates and returns the Ring application handler for public SSR views."
  [node]
  (let [router (ring/router (create-routes node))]
    (ring/ring-handler
     router
     (ring/create-default-handler
      {:not-found (fn [_]
                    (layout/error-response 404 "Not Found" "The requested URL was not found on this server."))
       :method-not-allowed (fn [_]
                             (layout/error-response 405 "Method Not Allowed" "HTTP method not supported for this route."))})
     {:middleware [params/parameters-middleware]})))
