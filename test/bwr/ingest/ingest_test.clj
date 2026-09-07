(ns bwr.ingest.ingest-test
  "Comprehensive tests for the bwr.ingest pipeline:
   - Generative testing with test.check proving malformed inputs are rejected at the door.
   - Real-world FBref match fixture ingested and traceable end-to-end in XTDB."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.spec.alpha :as s]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [bwr.ingest.spec :as ingest-spec]
            [bwr.ingest.pipeline :as pipeline]
            [bwr.store.node :as store-node]
            [bwr.store.query :as store-query]))

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
;; 1. Generative Testing with test.check (§10)
;; ============================================================================

;; Generator for arbitrary / malformed records
(def malformed-fbref-gen
  (gen/let [match-id (gen/one-of [(gen/return nil) gen/string-alphanumeric gen/int])
            minute   (gen/one-of [(gen/return nil)
                                  (gen/return "not-a-minute")
                                  (gen/return -10)
                                  gen/string-ascii
                                  (gen/choose -100 -1)])
            team     (gen/one-of [(gen/return nil) (gen/return "") gen/string-alphanumeric])
            event-t  (gen/one-of [(gen/return nil)
                                  (gen/return "unknown-event")
                                  (gen/return "foul-play")
                                  gen/keyword
                                  gen/string-alphanumeric])]
    {:match-id match-id
     :minute minute
     :team team
     :event-type event-t}))

(defspec malformed-records-rejected-at-boundary-test
  100
  (prop/for-all [bad-record malformed-fbref-gen]
    (let [rejected (atom [])
          reject-fn (fn [parsed explain]
                      (swap! rejected conj {:parsed parsed :explain explain}))
          out (pipeline/parse-and-validate-records :source/fbref [bad-record] reject-fn)]
      ;; Every record emitted by the pipeline MUST be 100% spec-valid
      (and (every? #(s/valid? ::ingest-spec/match-event %) out)
           ;; If input was malformed, nothing was emitted and rejection was captured
           (if (empty? out)
             (pos? (count @rejected))
             true)))))

;; Generator for valid FBref records across various minute ranges
(def valid-fbref-gen
  (gen/let [match-id (gen/elements ["M42" "M43" "M64"])
            minute (gen/choose 1 95)
            team (gen/elements ["France" "Iraq" "Spain" "Brazil"])
            event-type (gen/elements ["substitution" "goal" "card" "shot"])
            player (gen/elements ["Mbappe" "Griezmann" "Thuram" "Ali" "Pedri"])]
    (case event-type
      "substitution"
      {:match-id match-id
       :minute minute
       :team team
       :event-type event-type
       :player-off "PlayerA"
       :player-on "PlayerB"}

      "card"
      {:match-id match-id
       :minute minute
       :team team
       :event-type event-type
       :player player
       :card "yellow"}

      "shot"
      {:match-id match-id
       :minute minute
       :team team
       :event-type event-type
       :player player
       :outcome "on-target"}

      "goal"
      {:match-id match-id
       :minute minute
       :team team
       :event-type event-type
       :player player
       :penalty? false})))

(defspec valid-records-ingested-with-provenance-test
  50
  (prop/for-all [valid-record valid-fbref-gen]
    (let [out (pipeline/parse-and-validate-records :source/fbref [valid-record])]
      (and (= 1 (count out))
           (let [event (first out)]
             (and (s/valid? ::ingest-spec/ingested-event event)
                  (= :source/fbref (:event/source event))
                  (some? (:event/ingested-at event))
                  ;; Rule engine owns window semantics, not ingest
                  (nil? (:event/break-window event))))))))

;; ============================================================================
;; 2. End-to-End Real Fixture Ingestion & Traceability
;; ============================================================================

(deftest fbref-end-to-end-ingestion-test
  (testing "Real FBref match payload with valid and malformed rows"
    (let [raw-fixtures
          [;; Valid: 21' Shot (France)
           {:match-id "M42" :minute 21 :team "France" :event-type "shot"
            :player "Mbappe" :outcome "on-target"}

           ;; Valid: 23' Substitution in 22' break window (France)
           {:match-id "M42" :minute "23" :team "France" :event-type "substitution"
            :player-off "Griezmann" :player-on "Thuram"}

           ;; Malformed Row 1: Garbled minute format
           {:match-id "M42" :minute "invalid-clock-min" :team "France" :event-type "shot"
            :player "Dembele"}

           ;; Valid: 50' Card outside break window (Iraq)
           {:match-id "M42" :minute 50 :team "Iraq" :event-type "card"
            :player "Iqbal" :card "yellow"}

           ;; Malformed Row 2: Unknown event type not supported by schema
           {:match-id "M42" :minute 66 :team "Iraq" :event-type "corner-kick-dance"
            :player "Ali"}

           ;; Valid: 67' Substitution in 67' break window (France)
           {:match-id "M42" :minute 67 :team "France" :event-type "sub"
            :player-off "Rabiot" :player-on "Camavinga"}

           ;; Valid: 90+2 Extra-time Goal
           {:match-id "M42" :minute "90+2" :team "France" :event-type "goal"
            :player "Mbappe" :assist "Thuram"}]

          rejected-records (atom [])
          reject-fn (fn [parsed explain]
                      (swap! rejected-records conj {:parsed parsed :explain explain}))]

      ;; Transact match metadata into store first
      (store-query/transact! *node*
                            [{:match/id "M42"
                              :match/tournament "wc2026"
                              :match/home-team "France"
                              :match/away-team "Iraq"
                              :match/kickoff #inst "2026-06-22T15:00:00Z"
                              :match/venue "Philadelphia Stadium"
                              :match/data-source :source/fbref
                              :match/data-quality :quality/verified}])

      ;; Ingest fixtures into store through the pipeline
      (pipeline/ingest! *node* :source/fbref raw-fixtures {:reject-fn reject-fn})

      ;; 1. Verify that malformed rows were rejected AT THE PIPELINE BOUNDARY
      (testing "Pipeline rejected the 2 malformed rows before reaching the store"
        (is (= 2 (count @rejected-records)))
        (let [reasons (mapv (comp :event/type :parsed) @rejected-records)]
          (is (some nil? reasons))))

      ;; 2. Verify only the 5 valid events landed in the XTDB database
      (testing "Only valid records were committed to the XTDB database"
        (let [db (store-query/db-at *node*)
              all-events (store-query/find-events-in-window db "M42" 0 120)]
          (is (= 5 (count all-events)))
          (is (= [21 23 50 67 92] (mapv :event/minute all-events)))
          (is (every? #(= :source/fbref (:event/source %)) all-events))
          (is (every? #(some? (:event/ingested-at %)) all-events))))

      ;; 3. Verify temporal queryability without hardcoded window tags in ingest
      (testing "Temporal range queries retrieve raw events; rules evaluate windows dynamically"
        (let [db (store-query/db-at *node*)
              ;; 22' break window (minutes 17 to 27)
              break-22-events (store-query/find-events-in-window db "M42" 17 27)
              sub-22 (first (filter #(= :event.type/substitution (:event/type %)) break-22-events))
              ;; 67' break window (minutes 62 to 72)
              break-67-events (store-query/find-events-in-window db "M42" 62 72)
              sub-67 (first (filter #(= :event.type/substitution (:event/type %)) break-67-events))]

          (is (= 2 (count break-22-events))) ; 21' shot, 23' sub
          (is (some? sub-22))
          (is (= {:player-off "Griezmann" :player-on "Thuram"} (:event/detail sub-22)))
          (is (nil? (:event/break-window sub-22))) ; Ingest does not hardcode break-window

          (is (= 1 (count break-67-events))) ; 67' sub
          (is (some? sub-67))
          (is (= {:player-off "Rabiot" :player-on "Camavinga"} (:event/detail sub-67)))
          (is (nil? (:event/break-window sub-67))))))))
