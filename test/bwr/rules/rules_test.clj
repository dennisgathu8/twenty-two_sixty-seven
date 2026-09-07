(ns bwr.rules.rules-test
  "Comprehensive tests for the bwr.rules core.logic engine and EDN loader (§7):
   - Rule EDN loading and schema validation
   - Positive fixture firing with non-empty evidence trail
   - Adjacent negative fixtures NOT firing (timing boundaries, wrong event types, wrong teams)
   - Quality gate enforcement (§5.3)
   - End-to-end store integration and evidence ID resolution to real entities in XTDB."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.spec.alpha :as s]
            [bwr.store.node :as store-node]
            [bwr.store.schema :as schema]
            [bwr.store.query :as store-query]
            [bwr.rules.loader :as loader]
            [bwr.rules.engine :as engine]))

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
;; Test Fixtures
;; ============================================================================

(def test-match
  {:match/id "M42"
   :match/tournament "wc2026"
   :match/home-team "France"
   :match/away-team "Iraq"
   :match/kickoff #inst "2026-06-22T15:00:00Z"
   :match/venue "Philadelphia Stadium"
   :match/data-source :source/fbref
   :match/data-quality :quality/verified})

(def test-stoppage-22
  {:stoppage/id "M42-stp-22"
   :stoppage/match "M42"
   :stoppage/type :stoppage.type/hydration
   :stoppage/half 1
   :stoppage/clock-minute 22
   :stoppage/duration-s 180})

;; ============================================================================
;; 1. EDN Rule Loading & Schema Validation (§7)
;; ============================================================================

(deftest rule-loader-test
  (testing "Loads and validates rule EDN from classpath resource"
    (let [rule (loader/load-rule-resource "rules/break_window_substitution_pattern.edn")]
      (is (= :rule/break-window-substitution-pattern (:rule/id rule)))
      (is (= "1.0.0" (:rule/version rule)))
      (is (= 300 (:rule/window-s rule)))
      (is (seq? (:rule/predicate rule)))
      (is (s/valid? ::schema/rule rule))))

  (testing "Rejects malformed rule definition missing required fields"
    (is (thrown? Exception
                 (loader/parse-rule
                  "{:rule/id :rule/broken
                    :rule/version \"1.0.0\"
                    ;; missing :rule/description, :rule/window-s, :rule/predicate
                    }")))))

;; ============================================================================
;; 2. Logic Relation & Boundary Fixtures (Positive & Adjacent Negatives)
;; ============================================================================

(deftest rule-engine-fixtures-test
  (let [rule (loader/load-rule-resource "rules/break_window_substitution_pattern.edn")]

    ;; ------------------------------------------------------------------------
    ;; Positive Fixture: Substitution at 23' inside 22' break window (17-27)
    ;; ------------------------------------------------------------------------
    (testing "Positive fixture: Substitution inside break window fires with valid recommendation"
      (let [positive-event {:event/id "evt-sub-23"
                            :event/match "M42"
                            :event/type :event.type/substitution
                            :event/minute 23
                            :event/team "France"
                            :event/detail {:player-off "Griezmann" :player-on "Thuram"}
                            :event/source :source/fbref}
            recs (engine/evaluate-rule-on-facts rule test-match test-stoppage-22 [positive-event])]
        (is (= 1 (count recs)))
        (let [rec (first recs)]
          (is (= "France" (:recommendation/team rec)))
          (is (= :rule/break-window-substitution-pattern (:recommendation/rule rec)))
          (is (= ["evt-sub-23"] (:recommendation/evidence rec)))
          (is (re-find #"France made a substitution inside the 22' break window at minute 23"
                       (:recommendation/text rec)))
          (is (s/valid? ::schema/recommendation rec)))))

    ;; ------------------------------------------------------------------------
    ;; Adjacent Negative Fixture 1: Substitution at 16' (1 minute before window 17-27)
    ;; ------------------------------------------------------------------------
    (testing "Adjacent negative fixture 1: Substitution at 16' (before window 17-27) does NOT fire"
      (let [adjacent-early-event {:event/id "evt-sub-16"
                                  :event/match "M42"
                                  :event/type :event.type/substitution
                                  :event/minute 16
                                  :event/team "France"
                                  :event/detail {:player-off "Griezmann" :player-on "Thuram"}
                                  :event/source :source/fbref}
            recs (engine/evaluate-rule-on-facts rule test-match test-stoppage-22 [adjacent-early-event])]
        (is (empty? recs))))

    ;; ------------------------------------------------------------------------
    ;; Adjacent Negative Fixture 2: Substitution at 28' (1 minute after window 17-27)
    ;; ------------------------------------------------------------------------
    (testing "Adjacent negative fixture 2: Substitution at 28' (after window 17-27) does NOT fire"
      (let [adjacent-late-event {:event/id "evt-sub-28"
                                 :event/match "M42"
                                 :event/type :event.type/substitution
                                 :event/minute 28
                                 :event/team "France"
                                 :event/detail {:player-off "Griezmann" :player-on "Thuram"}
                                 :event/source :source/fbref}
            recs (engine/evaluate-rule-on-facts rule test-match test-stoppage-22 [adjacent-late-event])]
        (is (empty? recs))))

    ;; ------------------------------------------------------------------------
    ;; Negative Fixture 3: Event at 23' inside window, but it is a CARD or SHOT, not a substitution
    ;; ------------------------------------------------------------------------
    (testing "Negative fixture 3: Card or Shot at 23' inside window does NOT fire substitution rule"
      (let [card-event {:event/id "evt-card-23"
                        :event/match "M42"
                        :event/type :event.type/card
                        :event/minute 23
                        :event/team "France"
                        :event/detail {:player "Mbappe" :card "yellow"}
                        :event/source :source/fbref}
            shot-event {:event/id "evt-shot-23"
                        :event/match "M42"
                        :event/type :event.type/shot
                        :event/minute 23
                        :event/team "France"
                        :event/detail {:player "Dembele"}
                        :event/source :source/fbref}
            recs (engine/evaluate-rule-on-facts rule test-match test-stoppage-22 [card-event shot-event])]
        (is (empty? recs))))

    ;; ------------------------------------------------------------------------
    ;; Negative Fixture 4: Substitution at 23' by the OTHER team (Iraq)
    ;; ------------------------------------------------------------------------
    (testing "Negative fixture 4: Substitution at 23' by Iraq produces recommendation for Iraq, not France"
      (let [iraq-sub {:event/id "evt-sub-iraq-23"
                      :event/match "M42"
                      :event/type :event.type/substitution
                      :event/minute 23
                      :event/team "Iraq"
                      :event/detail {:player-off "Ali" :player-on "Hussein"}
                      :event/source :source/fbref}
            recs (engine/evaluate-rule-on-facts rule test-match test-stoppage-22 [iraq-sub])]
        (is (= 1 (count recs)))
        (is (= "Iraq" (:recommendation/team (first recs))))
        (is (not-any? #(= "France" (:recommendation/team %)) recs))))

    ;; ------------------------------------------------------------------------
    ;; Quality Gate Enforcement (§5.3)
    ;; ------------------------------------------------------------------------
    (testing "Quality gate: Unverified match data produces ZERO recommendations (§5.3)"
      (let [unverified-match (assoc test-match :match/data-quality :quality/unverified)
            positive-event {:event/id "evt-sub-23"
                            :event/match "M42"
                            :event/type :event.type/substitution
                            :event/minute 23
                            :event/team "France"
                            :event/detail {:player-off "Griezmann" :player-on "Thuram"}
                            :event/source :source/fbref}
            recs (engine/evaluate-rule-on-facts rule unverified-match test-stoppage-22 [positive-event])]
        (is (nil? recs))))))

;; ============================================================================
;; 3. Evidence Invariant Enforcement (§7)
;; ============================================================================

(deftest evidence-trail-enforcement-test
  (testing "Engine rejects recommendation construction when evidence is empty (§7)"
    (let [rule (loader/load-rule-resource "rules/break_window_substitution_pattern.edn")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Rule evaluation produced zero evidence"
           (engine/build-recommendation
            {:match-id "M42"
             :stoppage-id "stp-22"
             :rule rule
             :team "France"
             :text "France substituted"
             :evidence-events []
             :tx 1})))))

  (testing "Schema rejects recommendation with empty evidence vector"
    (is (not (schema/valid-entity?
              {:recommendation/id "rec-1"
               :recommendation/match "M42"
               :recommendation/stoppage "stp-22"
               :recommendation/rule :rule/break-window-substitution-pattern
               :recommendation/rule-version "1.0.0"
               :recommendation/team "France"
               :recommendation/text "Some text"
               :recommendation/evidence [] ;; violates :min-count 1
               :recommendation/generated-at #inst "2026-06-22T16:00:00Z"
               :recommendation/tx 1})))))

;; ============================================================================
;; 4. End-to-End Store Integration & Evidence Resolution (§7)
;; ============================================================================

(deftest store-integration-and-evidence-resolution-test
  (testing "Rule evaluated against XTDB node produces recommendation whose evidence resolves to real store entities"
    (let [rule (loader/load-rule-resource "rules/break_window_substitution_pattern.edn")
          ;; Store entities
          sub-event {:event/id "M42-sub-23"
                     :event/match "M42"
                     :event/type :event.type/substitution
                     :event/minute 23
                     :event/team "France"
                     :event/detail {:player-off "Griezmann" :player-on "Thuram"}
                     :event/source :source/fbref}
          shot-event {:event/id "M42-shot-21"
                      :event/match "M42"
                      :event/type :event.type/shot
                      :event/minute 21
                      :event/team "France"
                      :event/detail {:player "Mbappe" :outcome "on-target"}
                      :event/source :source/fbref}
          late-sub-event {:event/id "M42-sub-67"
                          :event/match "M42"
                          :event/type :event.type/substitution
                          :event/minute 67
                          :event/team "France"
                          :event/detail {:player-off "Rabiot" :player-on "Camavinga"}
                          :event/source :source/fbref}]

      ;; 1. Transact rule, match, stoppage, and events into XTDB
      (loader/persist-rule! *node* rule)
      (store-query/transact! *node* [test-match
                                    test-stoppage-22
                                    sub-event
                                    shot-event
                                    late-sub-event])

      ;; 2. Evaluate rule over XTDB database snapshot
      (let [db (store-query/db-at *node*)
            recs (engine/evaluate-rule db rule "M42")]

        (is (= 1 (count recs)))
        (let [rec (first recs)
              evidence-ids (:recommendation/evidence rec)]

          (is (= "France" (:recommendation/team rec)))
          (is (= "M42-stp-22" (:recommendation/stoppage rec)))
          (is (= :rule/break-window-substitution-pattern (:recommendation/rule rec)))
          (is (= 1 (count evidence-ids)))
          (is (= "M42-sub-23" (first evidence-ids)))

          ;; 3. Transact recommendation into XTDB
          (store-query/transact! *node* [rec])

          ;; 4. Query recommendation from XTDB and RESOLVE evidence back to real entity
          (let [latest-db (store-query/db-at *node*)
                stored-recs (store-query/find-recommendations-for-match latest-db "M42")
                stored-rec (first stored-recs)
                evidence-id (first (:recommendation/evidence stored-rec))
                resolved-entity (store-query/entity latest-db evidence-id)]

            (is (= 1 (count stored-recs)))
            (is (some? resolved-entity))
            (is (= "M42-sub-23" (:event/id resolved-entity)))
            (is (= "France" (:event/team resolved-entity)))
            (is (= 23 (:event/minute resolved-entity)))
            (is (= :event.type/substitution (:event/type resolved-entity)))
            (is (= {:player-off "Griezmann" :player-on "Thuram"}
                   (:event/detail resolved-entity)))))))))
