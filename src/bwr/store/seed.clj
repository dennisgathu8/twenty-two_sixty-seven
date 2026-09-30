(ns bwr.store.seed
  "Deterministic test fixture seeding for Break-Window Response (§5, §8, §10).
   Populates verified match M42, scheduled hydration stoppages, match events,
   active declarative rules, and provenance-stamped recommendations."
  (:require [bwr.store.query :as query]
            [bwr.rules.loader :as loader]
            [bwr.rules.engine :as engine]))

(set! *warn-on-reflection* true)

(def match-m42
  {:match/id "M42"
   :match/tournament "wc2026"
   :match/home-team "France"
   :match/away-team "Iraq"
   :match/kickoff #inst "2026-06-22T15:00:00Z"
   :match/venue "Philadelphia Stadium"
   :match/data-source :source/fbref
   :match/data-quality :quality/verified})

(def stoppage-22
  {:stoppage/id "M42-stp-22"
   :stoppage/match "M42"
   :stoppage/type :stoppage.type/hydration
   :stoppage/half 1
   :stoppage/clock-minute 22
   :stoppage/duration-s 180})

(def stoppage-67
  {:stoppage/id "M42-stp-67"
   :stoppage/match "M42"
   :stoppage/type :stoppage.type/hydration
   :stoppage/half 2
   :stoppage/clock-minute 67
   :stoppage/duration-s 180})

(def event-shot-21
  {:event/id "M42-shot-21"
   :event/match "M42"
   :event/type :event.type/shot
   :event/minute 21
   :event/team "France"
   :event/detail {:player "Mbappe" :outcome "on-target"}
   :event/source :source/fbref})

(def event-sub-23
  {:event/id "M42-sub-23"
   :event/match "M42"
   :event/type :event.type/substitution
   :event/minute 23
   :event/team "France"
   :event/detail {:player-off "Griezmann" :player-on "Thuram"}
   :event/source :source/fbref})

(def event-sub-67
  {:event/id "M42-sub-67"
   :event/match "M42"
   :event/type :event.type/substitution
   :event/minute 67
   :event/team "France"
   :event/detail {:player-off "Rabiot" :player-on "Camavinga"}
   :event/source :source/fbref})

(defn seed-fixtures!
  "Seeds match M42, its stoppages, events, active rules, and recommendations
   into the provided XTDB node. Returns a summary map of seeded entity counts."
  [node]
  (let [;; 1. Transact match, stoppages, and events
        raw-docs [match-m42
                  stoppage-22
                  stoppage-67
                  event-shot-21
                  event-sub-23
                  event-sub-67]
        _ (query/transact! node raw-docs)

        ;; 2. Load and persist declarative rules from resources/rules
        rules (vals (loader/load-rules-from-resources))
        _ (doseq [r rules]
            (loader/persist-rule! node (assoc r :xt/id (:rule/id r))))

        ;; 3. Evaluate active rules over seeded facts to derive recommendations
        db (query/db-at node)
        recs (vec (mapcat #(engine/evaluate-rule db % "M42") rules))
        _ (when (seq recs)
            (query/transact! node recs))]
    {:matches 1
     :stoppages 2
     :events 3
     :rules (count rules)
     :recommendations (count recs)}))
