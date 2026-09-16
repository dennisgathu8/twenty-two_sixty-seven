(ns bwr.web.views.admin
  "Admin dashboard and operational controls SSR view (§8, §9).
   Provides rule status inspection, rule hot-reloading controls,
   and match data-quality override management with CSRF tokens."
  (:require [clojure.string :as str]
            [bwr.web.views.layout :as layout]))

(set! *warn-on-reflection* true)

(defn- format-inst
  "Formats a java.util.Date / instant to ISO UTC string."
  [^java.util.Date inst]
  (if-not inst
    "Initial Load"
    (let [sdf (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss 'UTC'")]
      (.setTimeZone sdf (java.util.TimeZone/getTimeZone "UTC"))
      (.format sdf inst))))

(defn- csrf-field
  "Generates a hidden anti-forgery token input field."
  [token]
  (when (and token (string? token))
    [:input {:type "hidden"
             :name "__anti-forgery-token"
             :value token}]))

(defn admin-dashboard-view
  "Renders the admin dashboard with rule status, hot-reload forms,
   and data-quality override management."
  [{:keys [identity rules matches status-msg error-msg anti-forgery-token]}]
  (layout/base-layout
   {:title "Strategy & Operations Admin — Break-Window Response"
    :description "Rule management, hot-reload, and data-quality overrides."}

   [:div.breadcrumb
    [:a {:href "/"} "← Public Match Index"]]

   ;; Admin Operator Header
   [:div.card
    [:h1 "Strategy & Operations Administration"]
    [:p.hero-lead
     "System management for FIFA 2026 break-window evaluation. "
     "Gated behind authenticated session; all operational actions are auditable in XTDB storage."]
    [:div.match-meta
     [:span [:strong "Operator: "] [:span.admin-operator-badge (or identity "authenticated-coach")]]
     [:span [:strong "Access Level: "] "Coaching Staff / Analyst"]
     [:span [:strong "Storage Engine: "] "XTDB 1.x (Bi-temporal)"]]]

   ;; Flash / Error Alerts
   (when status-msg
     [:div.alert.alert-success
      [:strong "Success: "] status-msg])

   (when error-msg
     [:div.alert.alert-error
      [:strong "Action Failed: "] error-msg])

   ;; Section 1: Rule Status & Hot-Reload Controls
   [:div.card
    [:h2.section-title "Declarative Rule Engine Status (§7, §8)"]
    [:p.section-desc
     "Rules evaluate break-window tactical substitution patterns via core.logic relations. "
     "Hot-reloading applies updated EDN definitions from resources/rules without restarting the server."]

    (if (empty? rules)
      [:p "No active rules discovered in store."]
      [:table.data-table
       [:thead
        [:tr
         [:th "Rule ID"]
         [:th "Version"]
         [:th "Window Size"]
         [:th "Last Reloaded"]
         [:th "Description"]
         [:th "Actions"]]]
       [:tbody
        (for [rule rules
              :let [rule-id (:rule/id rule)
                    rid-str (str rule-id)
                    clean-id (name rule-id)
                    reloaded-at (:rule/reloaded-at rule)]]
          [:tr {:key clean-id}
           [:td [:span.code-id rid-str]]
           [:td [:span.badge.badge-verified (:rule/version rule)]]
           [:td (str (:rule/window-s rule 300) "s")]
           [:td (format-inst reloaded-at)]
           [:td (:rule/description rule)]
           [:td
            [:form {:method "POST"
                    :action (str "/admin/rules/" clean-id "/reload")}
             (csrf-field anti-forgery-token)
             [:button.btn.btn-sm.btn-primary {:type "submit"} "Hot Reload"]]]])]])]

   ;; Section 2: Match Data-Quality Override Management
   [:div.card
    [:h2.section-title "Match Data-Quality Override Management (§8, §9)"]
    [:p.section-desc
     "Overrides match data quality tier. All overrides require an auditable reason "
     "and are recorded to the XTDB security audit log."]

    [:form {:method "POST"
            :action "/admin/matches/override"
            :id "data-quality-form"}
     (csrf-field anti-forgery-token)

     [:div.form-row
      [:div.form-group
       [:label.form-label {:for "match-select"} "Target Match:"]
       [:select.form-control {:name "match_id" :id "match-select" :required true}
        (if (empty? matches)
          [:option {:value ""} "No matches in storage"]
          (for [m matches
                :let [mid (:match/id m)
                      quality (name (or (:match/data-quality m) :unverified))]]
            [:option {:value mid}
             (str mid " (" (:match/home-team m) " vs " (:match/away-team m) ") — " quality)]))]]

      [:div.form-group
       [:label.form-label {:for "quality-select"} "New Quality Status:"]
       [:select.form-control {:name "quality" :id "quality-select" :required true}
        [:option {:value "verified"} "Verified Data (:quality/verified)"]
        [:option {:value "unverified"} "Unverified Data (:quality/unverified)"]
        [:option {:value "disputed"} "Disputed Data (:quality/disputed)"]]]

      [:div.form-group.form-group-flex
       [:label.form-label {:for "reason-input"} "Audit Reason (Required):"]
       [:input.form-control {:type "text"
                             :name "reason"
                             :id "reason-input"
                             :placeholder "e.g. Verified against official match commissioner report"
                             :required true}]]

      [:div.form-group
       [:button.btn.btn-warning {:type "submit"} "Apply Override"]]]]]))
