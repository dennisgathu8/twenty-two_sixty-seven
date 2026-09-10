(ns bwr.web.views.teams
  "Server-rendered HTML view for cross-match team break-window profiles (§8).
   Aggregates auditable tendencies and recommendations across matches for a team."
  (:require [bwr.web.views.layout :as layout]))

(set! *warn-on-reflection* true)

(defn team-break-profile-view
  "Renders the aggregated team-level break-window profile SSR view.
   Displays cross-match tendencies, break-window substitution frequency,
   and links to individual match reports and stoppage evidence."
  [{:keys [team recommendations matches]}]
  (let [title (str team " — Break-Window Response Profile")
        rec-count (count recommendations)
        matches-count (count matches)
        subs-in-breaks (count (filter #(= :rule/break-window-substitution-pattern (:recommendation/rule %)) recommendations))]
    (layout/base-layout
     {:title title
      :description (str "Aggregated break-window response profile for " team " across " matches-count " matches.")
      :og-type "profile"
      :canonical-url (str "/teams/" team "/break-profile")}

     [:div.breadcrumb
      [:a {:href "/"} "← All Matches"]]

     ;; Team Header Card
     [:div.card
      [:h1 (str team " — Break-Window Tendency Profile")]
      [:p.team-subtitle
       "Cross-match analysis of how " [:strong team] " utilizes mandatory FIFA 2026 hydration breaks (22' & 67')."]

      ;; Tendencies Summary Metrics
      [:div.metrics-grid
       [:div.metric-card.metric-neutral
        [:div.metric-label "Matches Analyzed"]
        [:div.metric-value matches-count]]
       [:div.metric-card.metric-primary
        [:div.metric-label "Break Window Tendencies"]
        [:div.metric-value rec-count]]
       [:div.metric-card.metric-accent
        [:div.metric-label "Break Subs Triggered"]
        [:div.metric-value subs-in-breaks]]]]

     ;; Aggregated Recommendations History
     [:div.card
      [:h2.section-title "Aggregated Break-Window Recommendations"]
      (if (empty? recommendations)
        [:p "No break-window tendencies recorded for " team " in available verified matches."]
        [:div
         (for [rec recommendations
               :let [mid (:recommendation/match rec)
                     sid (:recommendation/stoppage rec)
                     evidence (:recommendation/evidence rec)]]
           [:div.recommendation-box {:key (:recommendation/id rec)}
            [:div.recommendation-title
             (str "Match: " mid " — Stoppage: " sid)]
            [:div.recommendation-rule
             (str "Rule: " (name (:recommendation/rule rec)) " (v" (:recommendation/rule-version rec) ")")]
            [:div.recommendation-text (:recommendation/text rec)]
            [:div.evidence-trail
             [:strong "Evidence Trail: "]
             (for [eid evidence]
               [:span.evidence-item {:key eid}
                [:a.code-id {:href (str "/matches/" mid "/stoppages/" sid "#evt-" eid)} eid]])
             [:span.drilldown-link
              [:a {:href (str "/matches/" mid "/stoppages/" sid)} "Drill into Stoppage →"]]
             [:span.drilldown-link
              [:a {:href (str "/matches/" mid)} "View Match Report →"]]]])])]

     ;; Matches Involved
     [:div.card
      [:h2.section-title "Matches Analyzed"]
      (if (empty? matches)
        [:p "No matches found."]
        [:table.data-table
         [:thead
          [:tr
           [:th "Match ID"]
           [:th "Fixture"]
           [:th "Venue"]
           [:th "Report"]]]
         [:tbody
          (for [m matches
                :let [mid (:match/id m)]]
            [:tr {:key mid}
             [:td [:span.code-id mid]]
             [:td (str (:match/home-team m) " vs " (:match/away-team m))]
             [:td (:match/venue m)]
             [:td [:a {:href (str "/matches/" mid)} "Full Match Report →"]]])]])])))
