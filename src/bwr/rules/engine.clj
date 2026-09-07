(ns bwr.rules.engine
  "Declarative rule evaluation engine powered by core.logic (§7).
   Evaluates EDN rule definitions over match stoppage and event relations.
   Enforces data-quality gate (§5.3) and strict non-empty evidence trails (§7)."
  (:require [clojure.core.logic :as l]
            [clojure.core.logic.pldb :as pldb]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [xtdb.api :as xt]
            [bwr.store.schema :as schema]
            [bwr.store.query :as store-query]))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Relational Schema (core.logic.pldb)
;; ============================================================================

(pldb/db-rel breaks stoppage team)
(pldb/db-rel events-in-window stoppage event)

;; ============================================================================
;; Relational Logic Predicates (§7)
;; ============================================================================

(defn break-adjacent-substitution
  "core.logic relation identifying a substitution made by a team within a stoppage window.
   Corresponds to :rule/break-window-substitution-pattern."
  [team stoppage event]
  (l/all
   (breaks stoppage team)
   (events-in-window stoppage event)
   (l/featurec event {:event/type :event.type/substitution
                      :event/team team})))

(def predicate-registry
  "Registry mapping rule predicate symbols to their core.logic relational functions."
  {'break-adjacent-substitution break-adjacent-substitution})

;; ============================================================================
;; Window & Fact Construction
;; ============================================================================

(defn stoppage-window-range
  "Calculates the [start-minute end-minute] clock range for a stoppage given window-s seconds."
  [stoppage window-s]
  (let [cm (:stoppage/clock-minute stoppage)
        delta (int (Math/ceil (/ (or window-s 300) 60.0)))]
    [(max 0 (- cm delta)) (+ cm delta)]))

(defn filter-events-in-window
  "Filters a collection of events whose :event/minute falls within [start-minute, end-minute]."
  [events start-minute end-minute]
  (filterv (fn [evt]
             (let [m (:event/minute evt)]
               (and (number? m) (<= start-minute m end-minute))))
           events))

(defn build-logic-db
  "Constructs a pldb database containing facts for stoppages, teams, and candidate window events."
  [stoppages teams candidate-events-by-stoppage]
  (let [break-facts (for [s stoppages
                          t teams]
                      [breaks (:stoppage/id s) t])
        event-facts (for [s stoppages
                          evt (get candidate-events-by-stoppage (:stoppage/id s) [])]
                      [events-in-window (:stoppage/id s) evt])]
    (apply pldb/db (concat break-facts event-facts))))

;; ============================================================================
;; Recommendation Formatting & Assembly
;; ============================================================================

(defn format-recommendation-text
  "Formats user-facing recommendation rationale citing the stoppage and events."
  [rule team stoppage events]
  (let [evt (first events)
        m (:event/minute evt)
        detail (:event/detail evt)
        po (:player-off detail)
        pi (:player-on detail)]
    (if (and po pi)
      (str team " made a substitution inside the " (:stoppage/clock-minute stoppage)
           "' break window at minute " m " (" po " -> " pi ").")
      (str team " made a substitution inside the " (:stoppage/clock-minute stoppage)
           "' break window at minute " m "."))))

(defn build-recommendation
  "Constructs a validated recommendation document with a non-empty evidence trail.
   Enforces that evidence must contain at least one valid entity ID.
   Throws ex-info if evidence is empty or document fails schema validation."
  [{:keys [match-id stoppage-id rule team text evidence-events tx]}]
  (let [evidence-ids (mapv :event/id evidence-events)]
    (when (empty? evidence-ids)
      (throw (ex-info "Rule evaluation produced zero evidence; recommendation rejected by engine (§7)"
                      {:rule (:rule/id rule) :team team :stoppage stoppage-id})))
    (let [rec-id (str match-id "-rec-"
                      (name (:rule/id rule)) "-"
                      stoppage-id "-"
                      (str/replace (str/lower-case (str team)) #"\s+" "-"))
          rec {:recommendation/id rec-id
               :recommendation/match match-id
               :recommendation/stoppage stoppage-id
               :recommendation/rule (:rule/id rule)
               :recommendation/rule-version (:rule/version rule)
               :recommendation/team team
               :recommendation/text text
               :recommendation/evidence evidence-ids
               :recommendation/generated-at (java.util.Date.)
               :recommendation/tx (or tx 1)}]
      (schema/validate-entity! rec))))

;; ============================================================================
;; Rule Evaluation
;; ============================================================================

(defn resolve-predicate
  "Resolves the core.logic relation for a given rule."
  [rule]
  (let [pred-form (:rule/predicate rule)
        pred-sym (if (seq? pred-form) (first pred-form) pred-form)]
    (or (get predicate-registry pred-sym)
        (throw (ex-info (str "Unrecognized rule predicate: " pred-sym)
                        {:predicate pred-form :rule rule})))))

(defn evaluate-rule-on-facts
  "Pure in-memory relational evaluation of a rule against provided match, stoppage, and event facts.
   Useful for testing and offline analysis without database dependencies."
  [rule match stoppage events]
  (when (= :quality/verified (:match/data-quality match))
    (let [pred-fn (resolve-predicate rule)
          window-s (or (:rule/window-s rule) 300)
          [start-min end-min] (stoppage-window-range stoppage window-s)
          window-events (filter-events-in-window events start-min end-min)
          teams [(:match/home-team match) (:match/away-team match)]
          db-facts (build-logic-db [stoppage] teams {(:stoppage/id stoppage) window-events})
          solutions (pldb/with-db db-facts
                      (l/run* [q]
                        (l/fresh [?team ?stoppage ?event]
                          (pred-fn ?team ?stoppage ?event)
                          (l/== q {:team ?team :stoppage ?stoppage :event ?event}))))]
      (->> solutions
           (group-by :team)
           (keep (fn [[team team-solutions]]
                   (let [matching-events (mapv :event team-solutions)]
                     (when (seq matching-events)
                       (build-recommendation
                        {:match-id (:match/id match)
                         :stoppage-id (:stoppage/id stoppage)
                         :rule rule
                         :team team
                         :text (format-recommendation-text rule team stoppage matching-events)
                         :evidence-events matching-events
                         :tx 1})))))))))

(defn evaluate-rule
  "Evaluates a rule against a match in XTDB at the current db snapshot.
   Enforces:
     1. Quality gate: match must be :quality/verified (§5.3).
     2. Window semantics derived from rule's :rule/window-s.
     3. Non-empty evidence trail for every fired recommendation (§7).
   Returns a vector of validated recommendation maps."
  [db rule match-id]
  (let [match (store-query/find-match db match-id)]
    (when-not match
      (throw (ex-info (str "Match not found: " match-id) {:match-id match-id})))
    (if-not (= :quality/verified (:match/data-quality match))
      (do
        (log/info "Skipping recommendation evaluation for unverified match:"
                  {:match-id match-id :quality (:match/data-quality match)})
        [])
      (let [pred-fn (resolve-predicate rule)
            window-s (or (:rule/window-s rule) 300)
            stoppages (store-query/find-stoppages-by-match db match-id)
            teams [(:match/home-team match) (:match/away-team match)]
            db-basis (xt/db-basis db)
            tx-val (or (get-in db-basis [:tx :xtdb.api/tx-id])
                       (get db-basis :xtdb.api/valid-time)
                       (java.util.Date.))]
        (->> stoppages
             (mapcat (fn [stoppage]
                       (let [[start-m end-m] (stoppage-window-range stoppage window-s)
                             candidate-events (store-query/find-events-in-window db match-id start-m end-m)
                             db-facts (build-logic-db [stoppage] teams {(:stoppage/id stoppage) candidate-events})
                             solutions (pldb/with-db db-facts
                                         (l/run* [q]
                                           (l/fresh [?team ?stoppage ?event]
                                             (pred-fn ?team ?stoppage ?event)
                                             (l/== q {:team ?team :stoppage ?stoppage :event ?event}))))]
                         (->> solutions
                              (group-by :team)
                              (keep (fn [[team team-solutions]]
                                      (let [matching-events (mapv :event team-solutions)]
                                        (when (seq matching-events)
                                          (build-recommendation
                                           {:match-id match-id
                                            :stoppage-id (:stoppage/id stoppage)
                                            :rule rule
                                            :team team
                                            :text (format-recommendation-text rule team stoppage matching-events)
                                            :evidence-events matching-events
                                            :tx tx-val})))))))))
             (into []))))))

(defn evaluate-rules-for-match
  "Evaluates multiple rules against a match in XTDB, returning all generated recommendations."
  [db rules match-id]
  (into [] (mapcat #(evaluate-rule db % match-id) rules)))
