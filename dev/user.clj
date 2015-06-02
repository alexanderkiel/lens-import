(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [<! >! <!! >!!]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [lens.api :as api]
            [lens.event-bus :as bus :refer [publish!]]
            [lens.parse :refer [parse!]]
            [system]))

(def system nil)

(defn init []
  (alter-var-root #'system (constantly (system/system (system/env)))))

(defn start []
  (alter-var-root #'system system/start))

(defn stop []
  (alter-var-root #'system system/stop))

(defn startup []
  (init)
  (start)
  (println "Server running at port" (:port system)))

(defn reset []
  (stop)
  (refresh :after 'user/startup))

(defrecord StudyImporter [bus]
  component/Lifecycle
  (start [this]
    (->> (bus/listen-on (:publication bus) :study
           (fnk [id :as study]
             (let [uri (api/create-or-update-study! study)]
               (println "got study" id "at" uri)
               (publish! (:publisher bus) :study-uri {:id id :uri uri}))))
         (assoc this :stop-fn)))
  (stop [this]
    ((:stop-fn this))
    (dissoc this :stop-fn)))

(defn study-importer []
  (map->StudyImporter {}))

(defn system []
  (component/system-map
    :bus (bus/bus)
    :study-importer (component/using (study-importer) [:bus])))

(bus/listen-on publication :form-def-with-study-uri
  (fnk [:as form-def]
    (api/create-or-update-form-def! form-def)))

(defn handle-study-uri [state {:keys [id uri]}]
  (println "new study uri:" id "->" uri)
  (-> (update-in state [:study-uris] #(assoc % id uri))
      (update-in
        [:pending-form-defs]
        #(reduce
          (fn [pending-form-defs form-def]
            (if (= id (:study-id form-def))
              (publish! publisher :form-def-with-study-uri
                        (assoc form-def :study-uri uri))
              (conj pending-form-defs form-def)))
          []
          %))))

(defn handle-form-def [{:keys [study-uris] :as state}
                       {:keys [study-id] :as form-def}]
  (if-let [study-uri (study-uris study-id)]
    (do
      (publish! publisher :form-def-with-study-uri
                (assoc form-def :study-uri study-uri))
      state)
    (do
      (println "can't find study" study-id "-> add form-def to pendings")
      (update-in state [:pending-form-defs] #(conj % form-def)))))

(bus/listen-on-mult publication
  {:study-uri handle-study-uri
   :form-def handle-form-def}
  {:study-uris {}
   :pending-form-defs []})

(comment

  (api/execute-query "http://localhost:5001/find-study" {:id "S.0000"})

  (->> "samples/9814_who_five_well-being_.ODM.xml"
       #_"samples/9840_nci_standard_adverse.ODM.xml"
       (io/input-stream)
       (parse! publisher)
       (<!!))

  )
