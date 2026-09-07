(ns bwr.store.store-test
  "Comprehensive tests for bwr.store schema validation, document transactions,
   domain Datalog queries, and point-in-time bi-temporal query reproducibility."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [bwr.store.schema :as schema]
            [bwr.store.node :as node]
            [bwr.store.query :as query]))

(def ^:dynamic *node* nil)

(defn with-test-node [f]
  (let [n (node/start-node! {:topology :in-memory})]
    (try
      (binding [*node* n]
        (f))
      (finally
        (node/stop-node! n)))))

(use-fixtures :each with-test-node)

;; ============================================================================
;; 1. Schema Validation Tests
;; ============================================================================

(deftest schema-validation-test
  (testing "Valid entities pass schema validation"
    (let [tournament {:tournament/id "wc2026"
                      :tournament/name "FIFA World Cup 2026"
                      :tournament/start-date #inst "2026-06-11T00:00:00Z"
                      :tournament/end-date #inst "2026-07-19T00:00:00Z"}
          match {:match/id "M42"
                 :match/tournament "wc2026"
                 :match/home-team "France"
                 :match/away-team "Iraq"
                 :match/kickoff #inst "2026-06-22T15:00:00Z"
                 :match/venue "Philadelphia Stadium"
                 :match/data-source :source/fbref
                 :match/data-quality :quality/verified}
          stoppage {:stoppage/id "M42-break-1"
                    :stoppage/match "M42"
                    :stoppage/type :stoppage.type/hydration
                    :stoppage/half 1
                    :stoppage/clock-minute 22
                    :stoppage/duration-s 180}
          event {:event/id "M42-evt-0113"
                 :event/match "M42"
                 :event/type :event.type/substitution
                 :event/minute 23
                 :event/team "France"
                 :event/detail {:player-off "Griezmann" :player-on "Thuram"}
                 :event/source :source/fbref}
          rule {:rule/id :rule/break-window-substitution-pattern
                :rule/version "1.0.0"
                :rule/description "Flags tendency to substitute in break window"
                :rule/window-s 300
                :rule/predicate '(break-adjacent-substitution ?team ?stoppage ?event)}
          rec {:recommendation/id "M42-rec-004"
               :recommendation/match "M42"
               :recommendation/stoppage "M42-break-1"
               :recommendation/rule :rule/break-window-substitution-pattern
               :recommendation/rule-version "1.0.0"
               :recommendation/team "France"
               :recommendation/text "France made substitution inside break window."
               :recommendation/evidence ["M42-evt-0113"]
               :recommendation/generated-at #inst "2026-06-22T10:04:11Z"
               :recommendation/tx 100}]
      (is (true? (schema/valid-entity? tournament)))
      (is (true? (schema/valid-entity? match)))
      (is (true? (schema/valid-entity? stoppage)))
      (is (true? (schema/valid-entity? event)))
      (is (true? (schema/valid-entity? rule)))
      (is (true? (schema/valid-entity? rec)))))

;; ============================================================================
;; 2. Transact Boundary & Negative-Path Spec Rejection Tests
;; ============================================================================

(deftest transact-spec-rejection-test
  (testing "transact! rejects recommendation with empty evidence and does NOT write to store"
    (let [invalid-rec {:recommendation/id "M42-rec-empty-evidence"
                       :recommendation/match "M42"
                       :recommendation/stoppage "M42-break-1"
                       :recommendation/rule :rule/break-window-substitution-pattern
                       :recommendation/rule-version "1.0.0"
                       :recommendation/team "France"
                       :recommendation/text "France made substitution inside break window."
                       :recommendation/evidence [] ;; EMPTY EVIDENCE - strictly forbidden!
                       :recommendation/generated-at #inst "2026-06-22T10:04:11Z"
                       :recommendation/tx 10}]
      ;; 1. Assert spec rejects it
      (is (false? (schema/valid-entity? invalid-rec)))

      ;; 2. Assert transact! throws ex-info citing schema validation failure
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (query/transact! *node* [invalid-rec])))]
        (is (re-find #"Document failed schema validation for :bwr.store.schema/recommendation"
                     (ex-message ex))))

      ;; 3. Assert document was rejected at the door and NEVER reached XTDB
      (let [db (query/db-at *node*)]
        (is (nil? (query/entity db "M42-rec-empty-evidence"))))))

  (testing "transact! rejects stoppage with missing clock-minute and does NOT write to store"
    (let [invalid-stoppage {:stoppage/id "M42-stoppage-missing-minute"
                            :stoppage/match "M42"
                            :stoppage/type :stoppage.type/hydration
                            :stoppage/half 1
                            ;; :stoppage/clock-minute is intentionally omitted
                            :stoppage/duration-s 180}]
      ;; 1. Assert spec rejects it
      (is (false? (schema/valid-entity? invalid-stoppage)))

      ;; 2. Assert transact! throws ex-info citing schema validation failure
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (query/transact! *node* [invalid-stoppage])))]
        (is (re-find #"Document failed schema validation for :bwr.store.schema/stoppage"
                     (ex-message ex))))

      ;; 3. Assert document was rejected before reaching the store
      (let [db (query/db-at *node*)]
        (is (nil? (query/entity db "M42-stoppage-missing-minute"))))))

  (testing "transact! rejects batch if ANY entity is invalid (atomic validation at door)"
    (let [valid-match {:match/id "M99"
                       :match/tournament "wc2026"
                       :match/home-team "Spain"
                       :match/away-team "Brazil"
                       :match/kickoff #inst "2026-06-25T18:00:00Z"
                       :match/venue "MetLife Stadium"
                       :match/data-source :source/fbref
                       :match/data-quality :quality/verified}
          invalid-event {:event/id "evt-bad"
                         :event/match "M99"
                         :event/type :event.type/substitution
                         :event/minute -10 ;; NEGATIVE MINUTE - invalid
                         :event/team "Spain"
                         :event/detail {}
                         :event/source :source/fbref}]
      ;; Transact batch containing one valid and one invalid document
      (is (thrown? clojure.lang.ExceptionInfo
                   (query/transact! *node* [valid-match invalid-event])))
      ;; Neither document should have reached the store
      (let [db (query/db-at *node*)]
        (is (nil? (query/entity db "M99")))
        (is (nil? (query/entity db "evt-bad"))))))))

;; ============================================================================
;; 3. Basic Storage & Datalog Queries
;; ============================================================================

(deftest store-and-datalog-queries-test
  (let [match {:match/id "M42"
               :match/tournament "wc2026"
               :match/home-team "France"
               :match/away-team "Iraq"
               :match/kickoff #inst "2026-06-22T15:00:00Z"
               :match/venue "Philadelphia Stadium"
               :match/data-source :source/fbref
               :match/data-quality :quality/verified}
        stoppage-1 {:stoppage/id "M42-break-1"
                    :stoppage/match "M42"
                    :stoppage/type :stoppage.type/hydration
                    :stoppage/half 1
                    :stoppage/clock-minute 22
                    :stoppage/duration-s 180}
        stoppage-2 {:stoppage/id "M42-break-2"
                    :stoppage/match "M42"
                    :stoppage/type :stoppage.type/hydration
                    :stoppage/half 2
                    :stoppage/clock-minute 67
                    :stoppage/duration-s 180}
        event-1 {:event/id "M42-evt-1"
                 :event/match "M42"
                 :event/type :event.type/shot
                 :event/minute 21
                 :event/team "France"
                 :event/detail {:player "Mbappe" :outcome :on-target}
                 :event/source :source/fbref}
        event-2 {:event/id "M42-evt-2"
                 :event/match "M42"
                 :event/type :event.type/substitution
                 :event/minute 23
                 :event/team "France"
                 :event/detail {:player-off "Griezmann" :player-on "Thuram"}
                 :event/source :source/fbref}
        event-outside {:event/id "M42-evt-outside"
                       :event/match "M42"
                       :event/type :event.type/card
                       :event/minute 50
                       :event/team "Iraq"
                       :event/detail {:card :yellow}
                       :event/source :source/fbref}
        rec {:recommendation/id "M42-rec-001"
             :recommendation/match "M42"
             :recommendation/stoppage "M42-break-1"
             :recommendation/rule :rule/break-window-substitution-pattern
             :recommendation/rule-version "1.0.0"
             :recommendation/team "France"
             :recommendation/text "France substitution inside break window."
             :recommendation/evidence ["M42-evt-2"]
             :recommendation/generated-at #inst "2026-06-22T10:04:11Z"
             :recommendation/tx 1}]

    ;; Transact all fixtures
    (query/transact! *node* [match stoppage-1 stoppage-2 event-1 event-2 event-outside rec])

    (let [db (query/db-at *node*)]
      (testing "find-match retrieves match document by id"
        (is (= "Philadelphia Stadium" (:match/venue (query/find-match db "M42")))))

      (testing "find-matches-by-tournament retrieves tournament matches"
        (let [matches (query/find-matches-by-tournament db "wc2026")]
          (is (= 1 (count matches)))
          (is (= "M42" (:match/id (first matches))))))

      (testing "find-stoppages-by-match returns stoppages ordered by half & minute"
        (let [stoppages (query/find-stoppages-by-match db "M42")]
          (is (= ["M42-break-1" "M42-break-2"]
                 (mapv :stoppage/id stoppages)))))

      (testing "find-events-in-window isolates events in break window"
        ;; 22' window: minutes 20 to 25
        (let [window-events (query/find-events-in-window db "M42" 20 25)]
          (is (= ["M42-evt-1" "M42-evt-2"]
                 (mapv :event/id window-events)))))

      (testing "find-recommendations-for-match and team"
        (let [recs-match (query/find-recommendations-for-match db "M42")
              recs-team (query/find-recommendations-for-team db "France")]
          (is (= 1 (count recs-match)))
          (is (= "M42-rec-001" (:recommendation/id (first recs-match))))
          (is (= ["M42-evt-2"] (:recommendation/evidence (first recs-match))))
          (is (= 1 (count recs-team))))))))

;; ============================================================================
;; 3. Bi-temporal & Point-in-Time Query Tests
;; ============================================================================

(deftest bitemporal-point-in-time-reproducibility-test
  (testing "Point-in-time queries return historical state as of valid-time"
    (let [t1 #inst "2026-06-22T14:30:00.000-00:00" ; Kickoff pre-match window
          t2 #inst "2026-06-22T17:00:00.000-00:00" ; Post-match revised window

          rec-kickoff {:recommendation/id "M42-rec-bt"
                       :recommendation/match "M42"
                       :recommendation/stoppage "M42-break-1"
                       :recommendation/rule :rule/break-window-substitution-pattern
                       :recommendation/rule-version "1.0.0"
                       :recommendation/team "France"
                       :recommendation/text "Initial kickoff assessment: like-for-like swap likely at 22'."
                       :recommendation/evidence ["M42-evt-historical-1"]
                       :recommendation/generated-at t1
                       :recommendation/tx 10}

          rec-postmatch {:recommendation/id "M42-rec-bt"
                         :recommendation/match "M42"
                         :recommendation/stoppage "M42-break-1"
                         :recommendation/rule :rule/break-window-substitution-pattern
                         :recommendation/rule-version "1.0.1"
                         :recommendation/team "France"
                         :recommendation/text "Post-match revision: team held substitution until 67' break."
                         :recommendation/evidence ["M42-evt-historical-1" "M42-evt-historical-2"]
                         :recommendation/generated-at t2
                         :recommendation/tx 20}]

      ;; 1. Transact recommendation valid at t1
      (query/transact-at! *node* [rec-kickoff] t1)

      ;; 2. Later, transact recommendation updated at t2
      (query/transact-at! *node* [rec-postmatch] t2)

      ;; 3. Query as of kickoff (t1)
      (let [db-at-t1 (query/db-at *node* t1)
            rec-at-t1 (query/entity db-at-t1 "M42-rec-bt")]
        (is (some? rec-at-t1))
        (is (= "1.0.0" (:recommendation/rule-version rec-at-t1)))
        (is (= "Initial kickoff assessment: like-for-like swap likely at 22'."
               (:recommendation/text rec-at-t1)))
        (is (= ["M42-evt-historical-1"] (:recommendation/evidence rec-at-t1))))

      ;; 4. Query as of post-match (t2)
      (let [db-at-t2 (query/db-at *node* t2)
            rec-at-t2 (query/entity db-at-t2 "M42-rec-bt")]
        (is (some? rec-at-t2))
        (is (= "1.0.1" (:recommendation/rule-version rec-at-t2)))
        (is (= "Post-match revision: team held substitution until 67' break."
               (:recommendation/text rec-at-t2)))
        (is (= ["M42-evt-historical-1" "M42-evt-historical-2"]
               (:recommendation/evidence rec-at-t2))))

      ;; 5. Verify entity history exposes both bi-temporal milestones
      (let [history (query/entity-history *node* "M42-rec-bt" :asc {:with-docs? true})]
        (is (= 2 (count history)))
        (let [[h1 h2] history]
          (is (= t1 (:xtdb.api/valid-time h1)))
          (is (= "1.0.0" (get-in h1 [:xtdb.api/doc :recommendation/rule-version])))
          (is (= t2 (:xtdb.api/valid-time h2)))
          (is (= "1.0.1" (get-in h2 [:xtdb.api/doc :recommendation/rule-version]))))))))
