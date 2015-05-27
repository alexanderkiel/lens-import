(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [<! >! <!! >!!]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [org.httpkit.client :as http]
            [lens.api :as api]
            [lens.parse :refer [parse]]))

(def publisher (async/chan))
(def publication (async/pub publisher #(:topic %)))

(defnk create-form! [id name study-id & more]
  (letk [[status {error nil}]
         @(api/post-form "http://localhost:5001/studies"
                     (merge {"id" id "name" name "study-id" study-id}
                            (select-keys more [:description])))]
    (if error
      (println "Failed creating study" id "error" error)
      (when (not= 201 status)
        (println "Failed creating study" id "status" status)))))

(comment

  (api/execute-query "http://localhost:5001/find-study" {:id "S.0000"})

  (let [ch (async/chan)]
    (async/sub publication :study ch)
    (async/go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (api/create-or-update-study! msg)
        (recur))))

  (let [ch (async/chan)]
    (async/sub publication :form ch)
    (async/go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (pprint msg)
        (recur))))

  (->> (io/input-stream "samples/9814_who_five_well-being_.ODM.xml")
       (parse publisher)
       (<!!))

  )
