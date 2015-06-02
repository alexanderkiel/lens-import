(ns lens.form-def
  (:use plumbing.core)
  (:require [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus]))

(defn publish-form-def-with-study-uri! [bus form-def study-uri]
  (->> (assoc form-def :study-uri study-uri)
       (bus/publish! bus :form-def-with-study-uri)))

(defn pending-form-defs-handler
  "Returns a function which reduces a seq of pending form defs by publishing
  every resolvable one under the topic :form-def-with-study-uri."
  [bus study-id study-uri]
  (fn [pending-form-defs]
    (reduce
      (fn [remaining-pending-form-defs form-def]
        (if (= study-id (:study-id form-def))
          (do
            (publish-form-def-with-study-uri! bus form-def study-uri)
            remaining-pending-form-defs)
          (conj remaining-pending-form-defs form-def)))
      []
      pending-form-defs)))

(defn study-uri-handler [bus]
  "Returns a function which handles mesages of topic :study-uri.

  State is a map of :study-uris and :pending-form-defs. Collects study URIs for
  later use. Tries to publish form defs with study URIs on topic
  :form-def-with-study-uri."
  (fn [state {:keys [id uri]}]
    (println "new study uri:" id "->" uri)
    (-> (update-in state [:study-uris] #(assoc % id uri))
        (update-in [:pending-form-defs] (pending-form-defs-handler bus id uri)))))

(defn form-def-handler
  "Returns a function which handles mesages of topic :form-def.

  State is a map of :study-uris and :pending-form-defs. Publishes form defs with
  already known study URIs to :form-def-with-study-uri. Collects the form def
  under :pending-form-defs otherwise."
  [bus]
  (fn [{:keys [study-uris] :as state}
       {:keys [study-id] :as form-def}]
    (if-let [study-uri (study-uris study-id)]
      (do
        (publish-form-def-with-study-uri! bus form-def study-uri)
        state)
      (do
        (println "can't find study" study-id "-> add form-def to pendings")
        (update-in state [:pending-form-defs] #(conj % form-def))))))

(defrecord FormDefStudyUriCoordinator [bus]
  component/Lifecycle
  (start [this]
    (->> (bus/listen-on-mult bus
           {:study-uri (study-uri-handler bus)
            :form-def (form-def-handler bus)}
           {:study-uris {}
            :pending-form-defs []})
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn form-def-study-uri-coordinator []
  (map->FormDefStudyUriCoordinator {}))

(defrecord FormDefImporter [bus]
  component/Lifecycle
  (start [this]
    (->> (bus/listen-on bus :form-def-with-study-uri
           (fnk [:as form-def]
             (api/create-or-update-form-def! form-def)))
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn form-def-importer []
  (map->FormDefImporter {}))
