(ns user
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [<! >! >!!]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [org.httpkit.client :as http]
            [lens.parse :refer [parse]]))

(def publisher (async/chan))
(def publication (async/pub publisher #(:topic %)))

(defn post-form [url params]
  (http/request {:method :post :url url :form-params params}))

(defnk create-study! [id name & more]
  (letk [[status {error nil}]
         @(post-form "http://localhost:5001/studies"
                     (merge {"id" id "name" name}
                            (select-keys more [:description])))]
    (if error
      (println "Failed creating study " id ": " error)
      (when (not= 201 status)
        (println "Failed creating study " id ": status " status)))))

(defnk create-form! [study-id id name & more]
  (letk [[status {error nil}]
         @(post-form "http://localhost:5001/studies"
                     (merge {"id" id "name" name}
                            (select-keys more [:description])))]
    (if error
      (println "Failed creating study " id ": " error)
      (when (not= 201 status)
        (println "Failed creating study " id ": status " status)))))

(comment

  (let [ch (async/chan)]
    (async/sub publication :study ch)
    (async/go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (create-study! msg)
        (recur))))

  (let [ch (async/chan)]
    (async/sub publication :form ch)
    (async/go-loop []
      (when-let [{:keys [msg]} (<! ch)]
        (pprint msg)
        (recur))))

  (parse publisher (io/input-stream "samples/9814_who_five_well-being_.ODM.xml"))
  )