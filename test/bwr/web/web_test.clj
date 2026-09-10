(ns bwr.web.web-test
  "Ring handler tests for bwr.web public SSR views (§8, §9, §10):
   - Real 200 responses with correct semantic HTML structure
   - Verification of Content-Security-Policy (CSP) headers and Subresource Integrity (SRI)
   - Auditable evidence drilldown on stoppage detail pages linking to real store entities
   - Aggregated cross-match team break profiles
   - Explicit 404 Not Found handling for unknown match, stoppage, and team IDs
   - Explicit 400 Bad Request boundary validation on malformed identifiers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [ring.mock.request :as mock]
            [bwr.store.node :as store-node]
            [bwr.store.query :as store-query]
            [bwr.web.views.layout :as layout]
            [bwr.web.routes :as routes]))

(def ^:dynamic *node* nil)

(defn with-test-store [f]
  (let [node (store-node/start-node! {:topology :in-memory})]
    (try
      (binding [*node* node]
        (f))
      (finally
        (store-node/stop-node! node)))))

(use-fixtures :each with-test-store)

;; ============================================================================
;; Test Data Fixtures
;; ============================================================================

(def fixture-match
  {:match/id "M42"
   :match/tournament "wc2026"
   :match/home-team "France"
   :match/away-team "Iraq"
   :match/kickoff #inst "2026-06-22T15:00:00Z"
   :match/venue "Philadelphia Stadium"
   :match/data-source :source/fbref
   :match/data-quality :quality/verified})

(def fixture-stoppage-22
  {:stoppage/id "M42-stp-22"
   :stoppage/match "M42"
   :stoppage/type :stoppage.type/hydration
   :stoppage/half 1
   :stoppage/clock-minute 22
   :stoppage/duration-s 180})

(def fixture-stoppage-67
  {:stoppage/id "M42-stp-67"
   :stoppage/match "M42"
   :stoppage/type :stoppage.type/hydration
   :stoppage/half 2
   :stoppage/clock-minute 67
   :stoppage/duration-s 180})

(def fixture-sub-23
  {:event/id "M42-sub-23"
   :event/match "M42"
   :event/type :event.type/substitution
   :event/minute 23
   :event/team "France"
   :event/detail {:player-off "Griezmann" :player-on "Thuram"}
   :event/source :source/fbref})

(def fixture-shot-21
  {:event/id "M42-shot-21"
   :event/match "M42"
   :event/type :event.type/shot
   :event/minute 21
   :event/team "France"
   :event/detail {:player "Mbappe" :outcome "on-target"}
   :event/source :source/fbref})

(def fixture-rec-22
  {:recommendation/id "M42-rec-sub-22"
   :recommendation/match "M42"
   :recommendation/stoppage "M42-stp-22"
   :recommendation/rule :rule/break-window-substitution-pattern
   :recommendation/rule-version "1.0.0"
   :recommendation/team "France"
   :recommendation/text "France made a substitution inside the 22' break window at minute 23 (Griezmann -> Thuram)."
   :recommendation/evidence ["M42-sub-23"]
   :recommendation/generated-at #inst "2026-06-22T16:00:00Z"
   :recommendation/tx 1})

(defn populate-test-data! [node]
  (store-query/transact! node [fixture-match
                               fixture-stoppage-22
                               fixture-stoppage-67
                               fixture-sub-23
                               fixture-shot-21
                               fixture-rec-22]))

;; ============================================================================
;; 1. Public SSR Views (200 OK with Semantic HTML & Security Scaffolding)
;; ============================================================================

(deftest match-report-200-test
  (testing "GET /matches/:id returns 200 with real semantic HTML structure, CSP, and evidence links"
    (populate-test-data! *node*)
    (let [app (routes/create-app *node*)
          req (mock/request :get "/matches/M42")
          resp (app req)
          body (:body resp)
          headers (:headers resp)]

      (is (= 200 (:status resp)))
      (is (str/starts-with? (get headers "Content-Type") "text/html"))

      ;; Verify CSP and Security Headers (§9.3)
      (is (contains? headers "Content-Security-Policy"))
      (let [csp (get headers "Content-Security-Policy")]
        (is (str/includes? csp "default-src 'self'"))
        (is (str/includes? csp "script-src 'self'"))
        (is (str/includes? csp "style-src 'self'"))
        (is (not (str/includes? csp "unsafe-inline"))))
      (is (= "nosniff" (get headers "X-Content-Type-Options")))
      (is (= "DENY" (get headers "X-Frame-Options")))

      ;; Verify NO inline style attributes in rendered HTML
      (is (not (str/includes? body "style=")))

      ;; Semantic HTML structure (§8)
      (is (str/includes? body "<!DOCTYPE html>"))
      (is (str/includes? body "<html lang=\"en\">"))
      (is (str/includes? body "<head>"))
      (is (str/includes? body "<header class=\"site-header\">"))
      (is (str/includes? body "<main class=\"container\" id=\"content\">"))
      (is (str/includes? body "<footer class=\"site-footer\">"))

      ;; Open Graph & Schema.org JSON-LD
      (is (str/includes? body "property=\"og:title\""))
      (is (str/includes? body "type=\"application/ld+json\""))
      (is (str/includes? body "\"@type\":\"SportsEvent\""))

      ;; Subresource Integrity (SRI) on stylesheet
      (is (str/includes? body "integrity=\"sha384-"))
      (is (str/includes? body "crossorigin=\"anonymous\""))

      ;; Match Content
      (is (str/includes? body "France vs Iraq"))
      (is (str/includes? body "Philadelphia Stadium"))
      (is (str/includes? body "Verified Data"))

      ;; Stoppages & drilldown link
      (is (str/includes? body "M42-stp-22"))
      (is (str/includes? body "/matches/M42/stoppages/M42-stp-22"))

      ;; Recommendations & Evidence Trail link
      (is (str/includes? body "France made a substitution inside the 22' break window"))
      (is (str/includes? body "M42-sub-23"))
      (is (str/includes? body "/matches/M42/stoppages/M42-stp-22#evt-M42-sub-23"))

      ;; Match Events Timeline
      (is (str/includes? body "id=\"evt-M42-sub-23\""))
      (is (str/includes? body "Griezmann"))
      (is (str/includes? body "Thuram")))))

(deftest stoppage-detail-with-evidence-drilldown-test
  (testing "GET /matches/:id/stoppages/:sid renders evidence drilldown linking to real store entities"
    (populate-test-data! *node*)
    (let [app (routes/create-app *node*)
          req (mock/request :get "/matches/M42/stoppages/M42-stp-22")
          resp (app req)
          body (:body resp)
          headers (:headers resp)]

      (is (= 200 (:status resp)))
      (is (contains? headers "Content-Security-Policy"))

      ;; Stoppage Metadata
      (is (not (str/includes? body "style=")))
      (is (str/includes? body "22' Hydration Break Window"))
      (is (str/includes? body "Half 1"))
      (is (str/includes? body "180s"))
      (is (str/includes? body "/matches/M42"))

      ;; Recommendation
      (is (str/includes? body "break-window-substitution-pattern"))
      (is (str/includes? body "France made a substitution inside the 22' break window"))

      ;; Auditable Evidence Trail with resolved store entity details
      (is (str/includes? body "Auditable Evidence Trail"))
      (is (str/includes? body "id=\"evt-M42-sub-23\""))
      (is (str/includes? body "Off: Griezmann → On: Thuram"))
      (is (str/includes? body "fbref"))
      (is (str/includes? body "Store Fact Verified")))))

(deftest team-break-profile-test
  (testing "GET /teams/:id/break-profile renders aggregated cross-match tendencies"
    (populate-test-data! *node*)
    (let [app (routes/create-app *node*)
          req (mock/request :get "/teams/France/break-profile")
          resp (app req)
          body (:body resp)]

      (is (= 200 (:status resp)))
      (is (not (str/includes? body "style=")))
      (is (str/includes? body "France — Break-Window Tendency Profile"))
      (is (str/includes? body "Matches Analyzed"))
      (is (str/includes? body "Break Window Tendencies"))
      (is (str/includes? body "M42"))
      (is (str/includes? body "France made a substitution inside the 22' break window"))
      (is (str/includes? body "/matches/M42/stoppages/M42-stp-22#evt-M42-sub-23")))))

(deftest home-page-test
  (testing "GET / renders match index with links to match reports"
    (populate-test-data! *node*)
    (let [app (routes/create-app *node*)
          req (mock/request :get "/")
          resp (app req)
          body (:body resp)]

      (is (= 200 (:status resp)))
      (is (not (str/includes? body "style=")))
      (is (str/includes? body "Break-Window Response"))
      (is (str/includes? body "France vs Iraq"))
      (is (str/includes? body "/matches/M42")))))

;; ============================================================================
;; 2. Explicit 404 Not Found Handling (Unknown IDs)
;; ============================================================================

(deftest not-found-404-test
  (testing "Unknown IDs return explicit 404 with structured error layout (no 500, no silent empty render)"
    (populate-test-data! *node*)
    (let [app (routes/create-app *node*)]

      (testing "Unknown match ID"
        (let [resp (app (mock/request :get "/matches/NONEXISTENT"))
              body (:body resp)]
          (is (= 404 (:status resp)))
          (is (str/includes? body "404"))
          (is (str/includes? body "Match Not Found"))
          (is (contains? (:headers resp) "Content-Security-Policy"))))

      (testing "Unknown stoppage ID for existing match"
        (let [resp (app (mock/request :get "/matches/M42/stoppages/NONEXISTENT-STP"))
              body (:body resp)]
          (is (= 404 (:status resp)))
          (is (str/includes? body "404"))
          (is (str/includes? body "Stoppage Not Found"))))

      (testing "Stoppage ID belonging to different match"
        (let [resp (app (mock/request :get "/matches/OTHER-MATCH/stoppages/M42-stp-22"))]
          (is (= 404 (:status resp)))))

      (testing "Unknown team name with no records in store"
        (let [resp (app (mock/request :get "/teams/NonExistentTeam/break-profile"))
              body (:body resp)]
          (is (= 404 (:status resp)))
          (is (str/includes? body "Team Not Found"))))

      (testing "Unmapped route returns 404"
        (let [resp (app (mock/request :get "/completely-bogus-route"))]
          (is (= 404 (:status resp))))))))

;; ============================================================================
;; 3. Explicit 400 Bad Request Handling (Boundary Validation §9.1)
;; ============================================================================

(deftest bad-request-400-test
  (testing "Malformed or invalid identifiers trigger boundary rejection (HTTP 400)"
    (populate-test-data! *node*)
    (let [app (routes/create-app *node*)]

      (testing "Match ID with spaces or special characters"
        (let [resp (app (mock/request :get "/matches/bad%20id%20with%20spaces"))]
          (is (= 400 (:status resp)))
          (is (str/includes? (:body resp) "Invalid match identifier"))))

      (testing "Match ID with script injection"
        (let [resp (app (mock/request :get "/matches/%3Cscript%3Ealert(1)%3C%2Fscript%3E"))]
          (is (= 400 (:status resp)))))

      (testing "Stoppage ID with invalid characters"
        (let [resp (app (mock/request :get "/matches/M42/stoppages/stp%24invalid%21"))]
          (is (= 400 (:status resp)))))

      (testing "Team ID with script tags"
        (let [resp (app (mock/request :get "/teams/invalid%3Cscript%3E/break-profile"))]
          (is (= 400 (:status resp)))))

      (testing "400 response includes CSP and security headers"
        (let [resp (app (mock/request :get "/matches/bad%20id"))]
          (is (= 400 (:status resp)))
          (is (contains? (:headers resp) "Content-Security-Policy")))))))

;; ============================================================================
;; 4. Subresource Integrity & Static Assets (§9.3)
;; ============================================================================

(deftest static-assets-and-sri-test
  (testing "Static CSS is served with security headers and matches SRI hash in layout"
    (let [app (routes/create-app *node*)
          resp (app (mock/request :get "/css/main.css"))
          body (:body resp)
          headers (:headers resp)]

      (is (= 200 (:status resp)))
      (is (str/starts-with? (get headers "Content-Type") "text/css"))
      (is (contains? headers "Content-Security-Policy"))

      ;; Verify SRI hash matches the content served
      (let [computed-sri (layout/compute-sri-hash body)]
        (is (= @layout/main-css-sri computed-sri))
        (is (str/starts-with? computed-sri "sha384-"))))))
