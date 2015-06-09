(ns lens.event-bus
  (:require [clojure.core.async :as async :refer [go go-loop <! >!]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]))

(defrecord Bus [name]
  component/Lifecycle
  (start [this]
    (let [publisher (async/chan)]
      (assoc this :publisher publisher
                  :publication (async/pub publisher #(:topic %)))))
  (stop [this]
    (async/close! (:publisher this))
    (dissoc this :publisher :publication)))

(defn bus
  "Creates a new event bus."
  [name]
  (->Bus name))

(defn publish!! [bus topic msg]
  {:pre [(:publisher bus)]}
  (log/debug "Publish on" (str (:name bus) "/" (name topic)) "<-" (pr-str msg))
  (async/>!! (:publisher bus) {:topic topic :msg msg}))

(defn publish-from! [bus topic ch]
  {:pre [(:publisher bus)]}
  (go
    (when-let [msg (<! ch)]
      (log/debug "Publish on" (str (:name bus) "/" (name topic)) "<-" (pr-str msg))
      (>! (:publisher bus) {:topic topic :msg msg}))))

(defn listen-on
  "Listens on a topic of the bus.

  Calls the callback with each message. Returns a function which when called
  unsubscribes from the topic."
  [bus topic callback]
  {:pre [(:publication bus)]}
  (let [ch (async/chan)]
    (async/sub (:publication bus) topic ch)
    (go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (callback msg)
        (recur)))
    #(async/unsub (:publication bus) topic ch)))

(defn listen-on-mult
  "Listens on multiple topics of the bus.

  Callbacks are reduce functions over one single immediate result starting with
  start. Callbacks are supplied in a map from topic to callback.

  Returns a function which when called unsubscribes from all topics.

  Example:

  Listens on :topic-a and :topic-b. Does something with messages of :topic-a
  incrementing a counter every time. Resets the counter on every occurence of
  arbitary messages of :topic-b.

  (listen-on-mult owner
    {:topic-a
     (fn [count msg]
       (do-something! msg)
       (inc count))
     :topic-b
     (fn [_ _]
       0)}
    0)"
  [bus topic-callback-map start]
  {:pre [(:publication bus)]}
  (let [ch (async/chan)]
    (doseq [[topic] topic-callback-map]
      (async/sub (:publication bus) topic ch))
    (go-loop [result start]
      (when-let [{:keys [topic msg]} (<! ch)]
        (recur ((topic-callback-map topic) result msg))))
    #(doseq [[topic] topic-callback-map]
      (async/unsub (:publication bus) topic ch))))

(defn wait-for
  ""
  [res-bus bus res-topic topic callback]
  {:pre [(:publication bus)]}
  (let [ch (async/chan)
        res-ch (async/chan)]
    (async/sub (:publication bus) topic ch)
    (async/sub (:publication res-bus) res-topic res-ch)
    (go-loop [res nil
              ports [res-ch]]
      (let [[{:keys [msg]} port] (async/alts! ports)]
        (when msg
          (condp = port
            ch (do (callback res msg) (recur res [ch]))
            res-ch (recur msg [ch])))))
    #(do
      (async/unsub (:publication bus) topic ch)
      (async/unsub (:publication res-bus) res-topic res-ch))))
