(defproject lens-import "0.1-SNAPSHOT"
  :description "A command line tool for importing ODM data into Lens Warehouse."
  :url "https://github.com/alexanderkiel/lens-import"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/tools.logging "0.3.1"]
                 [prismatic/plumbing "0.5.2"]
                 [prismatic/schema "1.0.4"]
                 [org.clojars.akiel/hap-client-clj "0.4"
                  :exclusions [org.clojure/clojurescript
                               com.cognitect/transit-cljs
                               com.cognitect/transit-js]]
                 [org.clojars.akiel/async-error "0.1"
                  :exclusions [org.clojure/clojurescript]]
                 [org.slf4j/slf4j-api "1.7.7"]
                 [ch.qos.logback/logback-classic "1.1.2"]]

  :profiles {:dev
             {:source-paths ["dev"]
              :dependencies [[org.clojure/tools.namespace "0.2.4"]
                             [criterium "0.4.3"]]
              :global-vars {*print-length* 20}}

             :production
             {:main lens.core}}

  :repl-options {:welcome (do
                            (println "   Docs: (doc function-name-here)")
                            (println "         (find-doc \"part-of-name-here\")")
                            (println "   Exit: Control+D or (exit) or (quit)")
                            (println "  Start: (startup)")
                            (println "Restart: (reset)"))})
