(ns lens.core
  (:use plumbing.core)
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.core.async :refer [<!!]]
            [async-error.core :refer [<??]]
            [clojure.java.io :as io]
            [hap-client.core :as hap]
            [lens.parse :refer [parse!]]
            [lens.import :refer [import!]]))

(def cli-options
  [["-w" "--warehouse-uri URI" "The Lens Warehouse URI to use"
    :validate [#(.startsWith % "http")
               "Database URI has to start with http."]]
   ["-h" "--help" "Show this help"]])

(defn usage [options-summary]
  (->> ["Usage: lens-import [options]"
        ""
        "Options:"
        options-summary
        ""]
       (str/join "\n")))

(defn error-msg [errors]
  (str/join "\n" errors))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (letk [[options arguments errors summary] (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      (exit 0 (usage summary))

      errors
      (exit 1 (error-msg errors))

      (nil? (:warehouse-uri options))
      (exit 1 "Missing Lens Warehouse URI."))

    (letk [[warehouse-uri] options]
      (println "Version:" (System/getProperty "lens-import.version"))
      (println "Max Memory:" (quot (.maxMemory (Runtime/getRuntime))
                                   (* 1024 1024)) "MB")
      (println "Num CPUs:" (.availableProcessors (Runtime/getRuntime)))
      (println "Warehouse:" warehouse-uri)
      (let [filename (first arguments)
            service-document (<?? (hap/fetch warehouse-uri))
            ch (parse! (io/input-stream filename))
            res (<!! (import! service-document 100 ch))]
        (if (instance? Throwable res)
          (println res)
          (println "Finished!"))))))
