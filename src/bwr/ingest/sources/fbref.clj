(ns bwr.ingest.sources.fbref
  "FBref match report event parser implementing bwr.ingest.sources.core/parse-record.
   Normalizes goals, cards, substitutions, and shots into spec-compliant match events."
  (:require [bwr.ingest.sources.core :refer [parse-record]]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- parse-minute
  "Parses clock minute from an integer or string (handles extra time like '45+2' -> 47).
   Returns nil on parse failure."
  [raw-min]
  (cond
    (integer? raw-min) raw-min
    (string? raw-min)
    (try
      (if (str/includes? raw-min "+")
        (let [[base extra] (str/split raw-min #"\+")]
          (+ (Integer/parseInt (str/trim base))
             (Integer/parseInt (str/trim extra))))
        (Integer/parseInt (str/trim raw-min)))
      (catch Exception _ nil))
    :else nil))

(defn- normalize-event-type
  "Maps string or keyword event types from FBref into domain :event/type keywords."
  [t]
  (let [normalized (cond
                     (keyword? t) (name t)
                     (string? t) (str/lower-case (str/trim t))
                     :else "")]
    (case normalized
      ("sub" "substitution" "substitute") :event.type/substitution
      ("goal" "score")                    :event.type/goal
      ("card" "yellow-card" "red-card")   :event.type/card
      ("shot" "attempt")                  :event.type/shot
      nil)))

(defn- generate-event-id
  "Generates a deterministic unique ID for an FBref event."
  [match-id minute team event-type detail]
  (let [detail-summary (cond
                         (:player-off detail) (str (:player-off detail) "-" (:player-on detail))
                         (:player detail) (str (:player detail))
                         :else "evt")]
    (str match-id "-fbref-" minute "-" (str/replace (str team) #"\s+" "_") "-"
         (name (or event-type :unknown)) "-"
         (Math/abs ^int (hash detail-summary)))))

(defn- normalize-detail
  "Builds a clean detail map based on normalized event type."
  [norm-type raw]
  (case norm-type
    :event.type/substitution
    (let [off (or (:player-off raw) (get raw "player_off"))
          on (or (:player-on raw) (get raw "player_on"))]
      (if (and off on)
        {:player-off (str off) :player-on (str on)}
        {}))

    :event.type/goal
    (let [p (or (:player raw) (get raw "player"))
          assist (or (:assist raw) (get raw "assist"))
          pen? (or (:penalty? raw) (get raw "penalty") false)]
      (cond-> {:player (str (or p "Unknown"))}
        assist (assoc :assist (str assist))
        pen? (assoc :penalty? true)))

    :event.type/card
    (let [p (or (:player raw) (get raw "player"))
          c (or (:card raw) (get raw "card") "yellow")
          card-kw (if (or (= c :red) (= (str/lower-case (str c)) "red"))
                    :red
                    :yellow)]
      {:player (str (or p "Unknown"))
       :card card-kw})

    :event.type/shot
    (let [p (or (:player raw) (get raw "player"))
          outcome (or (:outcome raw) (get raw "outcome") :on-target)
          outcome-kw (if (keyword? outcome) outcome (keyword (str/lower-case (str outcome))))]
      {:player (str (or p "Unknown"))
       :outcome outcome-kw})

    {}))

(defmethod parse-record :source/fbref
  [_source raw]
  (let [match-id (or (:match-id raw) (get raw "match_id") (:match raw))
        min-val (parse-minute (or (:minute raw) (get raw "minute")))
        team (let [t (or (:team raw) (get raw "team"))]
               (when (and (string? t) (not (str/blank? t)))
                 (str/trim t)))
        raw-type (or (:event-type raw) (get raw "event_type") (:type raw))
        norm-type (normalize-event-type raw-type)
        detail (normalize-detail norm-type raw)]
    {:event/id (if (and match-id min-val team norm-type)
                 (or (:id raw) (generate-event-id match-id min-val team norm-type detail))
                 "invalid-id")
     :event/match (str match-id)
     :event/type norm-type
     :event/minute min-val
     :event/team team
     :event/detail detail
     :event/source :source/fbref}))
