(ns lens.event-bus
  (:require [clojure.core.async :as async :refer [go go-loop <! >!]]
            [clojure.tools.logging :as log]
            [schema.core :as s :refer [Str]]
            [com.stuartsierra.component :as component]))

(defrecord RBus [name]
  component/Lifecycle
  (start [this]
    (let [publisher (async/chan)]
      (assoc this :publisher publisher
                  :publication (async/pub publisher :topic))))
  (stop [this]
    (async/close! (:publisher this))
    (dissoc this :publisher :publication)))

(def Bus
  (s/record RBus {:name Str}))

(defn bus
  "Creates a new event bus."
  [name]
  (->RBus name))

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
  "Waits for resources on res-bus/res-topic before invoking the callback with
  messages from bus/topic.

  The callback has to return a channel which closes after the callback has
  finished."
  [res-bus bus res-topic topic callback]
  {:pre [(:publication bus)]}
  (let [ch (async/chan)
        reset-ch (async/chan)
        res-ch (async/chan)]
    (async/sub (:publication bus) topic ch)
    (async/sub (:publication bus) res-topic reset-ch)
    (async/sub (:publication res-bus) res-topic res-ch)
    (log/debug "wait-for" topic "enter")
    (go-loop [res nil
              ports [reset-ch]
              callbacks (list)]
      (log/trace "wait-for" topic "select on"
                 (mapv {ch 'ch reset-ch 'reset-ch res-ch 'res-ch} ports)
                 "and" (count callbacks) "callback(s)")
      (let [[{:keys [msg] :as val} port] (async/alts! (into ports callbacks))]
        (if val
          (condp = port
            ch
            (do
              (log/trace "wait-for" topic "callback" (:id (:data res)) (:id msg))
              (let [callback (callback res msg)]
                (recur res
                       (if (< (count callbacks) 100)
                         [ch reset-ch]
                         [reset-ch])
                       (conj callbacks callback))))

            reset-ch
            (do (doseq [callback callbacks]
                  (<! callback))
                (recur nil [res-ch] (list)))

            res-ch (recur msg [ch reset-ch] (list))

            (recur res [ch reset-ch] (remove #{port} callbacks)))
          (log/debug "wait-for" topic "exit"))))
    #(do
      (async/unsub (:publication bus) topic ch)
      (async/unsub (:publication bus) res-topic reset-ch)
      (async/unsub (:publication res-bus) res-topic res-ch))))
