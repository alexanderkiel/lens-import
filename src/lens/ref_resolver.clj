(ns lens.ref-resolver
  "Component which resolves parents of ODM entities like studies of form-defs."
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [go-loop]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [lens.event-bus :as bus]))

(defn wait-for
  "parent-name: study-event, ...
   entity-name: form, item-group or item"
  [warehouse-bus parse-bus parent-name entity-name def-pred callback]
  {:pre [(:publication parse-bus)]}
  (let [parent-topic (keyword (str parent-name "-def"))
        ref-topic (keyword (str entity-name "-ref"))
        def-topic (keyword (str entity-name "-def"))
        ref-ch (async/chan)
        reset-ch (async/chan)
        parent-ch (async/chan)
        def-ch (async/chan)]
    (async/sub (:publication parse-bus) ref-topic ref-ch)
    (async/sub (:publication parse-bus) parent-topic reset-ch)
    (async/sub (:publication warehouse-bus) parent-topic parent-ch)
    (async/sub (:publication warehouse-bus) def-topic def-ch)
    (go-loop [parent nil
              refs []
              defs []
              ports [reset-ch def-ch]]
      (log/debug (str "Refs [" entity-name "]:")
                 (mapv (fn [{:keys [parent ref]}] [(:id parent) ref]) refs))
      (let [[{:keys [msg]} port] (async/alts! ports)]
        (when msg
          (condp = port

            ;; We get a new reference as msg
            ;;
            ;; Call callback if the def is available
            ;; Otherwise collect the ref with its parent
            ref-ch
            (if-let [def (some #(def-pred msg %) defs)]
              (do (callback parent msg def)
                  (recur parent refs defs [ref-ch reset-ch def-ch]))
              (recur parent (conj refs {:parent parent :ref msg}) defs
                     [ref-ch reset-ch def-ch]))

            reset-ch
            (recur nil refs defs [parent-ch])

            parent-ch
            (recur msg refs defs [ref-ch reset-ch def-ch])

            ;; We get a new def as msg
            ;;
            ;; Call callback on all refs which want this def
            ;; Collect the def
            def-ch
            (do (log/debug (str "Got def [" entity-name "]:") msg)
                (doseq [{:keys [parent ref]} (filter #(def-pred (:ref %) msg) refs)]
                  (callback parent ref msg))
                (recur parent (remove #(def-pred (:ref %) msg) refs) (conj defs msg)
                       (if parent [ref-ch reset-ch def-ch]
                                  [reset-ch def-ch])))))))
    #(do
      (async/unsub (:publication parse-bus) ref-topic ref-ch)
      (async/unsub (:publication parse-bus) parent-topic reset-ch)
      (async/unsub (:publication warehouse-bus) parent-topic parent-ch)
      (async/unsub (:publication warehouse-bus) def-topic def-ch))))

(defrecord RefResolver [warehouse-bus parse-bus parent-name entity-name
                        def-pred callback]
  component/Lifecycle
  (start [this]
    (->> (callback warehouse-bus)
         (wait-for warehouse-bus parse-bus parent-name entity-name def-pred)
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn ref-resolver [parent-name entity-name def-pred callback]
  (map->RefResolver {:parent-name parent-name :entity-name entity-name
                     :def-pred def-pred :callback callback}))
