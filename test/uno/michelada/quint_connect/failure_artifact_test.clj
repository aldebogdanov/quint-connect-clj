(ns uno.michelada.quint-connect.failure-artifact-test
  "What happens to a failure after it is found: it becomes a file. Producing
  one takes Quint and lives in end-to-end-test; here the result map is built by
  hand, around a real recorded trace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [uno.michelada.quint-connect.report :as report]
            [uno.michelada.quint-connect.test :as qt])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "quint-connect-" (into-array FileAttribute []))))

(def ^:private recorded (slurp "dev/fixtures/bank_run_0.itf.json"))

(def ^:private diverged
  {:ok?     false
   :seed    42
   :traces  5
   :cmd     ["quint" "run" "bank.qnt" "--mbt" "--seed=42"]
   :failure {:trace 3 :trace-name "run_3.itf.json" :trace-json recorded
             :step 2 :action "deposit"}})

(deftest saves-quints-own-bytes-under-a-name-that-identifies-the-run
  (let [dir   (temp-dir)
        saved (qt/save-failure! diverged {:dir dir})
        path  (get-in saved [:failure :saved])]
    (is (= (str (io/file dir "bank-seed42-trace3.itf.json")) path)
        "the spec, the seed and the trace index are all in the name")
    (is (= recorded (slurp path)) "written verbatim, not re-encoded")
    (is (= (dissoc (:failure diverged) :saved)
           (dissoc (:failure saved) :saved))
        "and nothing else about the result changes")))

(deftest the-name-is-deterministic-so-a-rerun-rewrites-one-file
  (let [dir (temp-dir)]
    (qt/save-failure! diverged {:dir dir})
    (qt/save-failure! diverged {:dir dir})
    (is (= 1 (count (.listFiles (io/file dir))))
        "same seed and index is the same trace; near-copies would pile up")))

(deftest the-name-can-be-given
  (let [dir   (temp-dir)
        saved (qt/save-failure! diverged {:dir dir :name "overdraft.itf.json"})]
    (is (= (str (io/file dir "overdraft.itf.json"))
           (get-in saved [:failure :saved])))))

(deftest a-passing-result-writes-nothing
  (let [dir    (str (io/file (temp-dir) "failures"))
        result {:ok? true :seed 42 :traces 5 :failure nil}]
    (is (= result (qt/save-failure! result {:dir dir})))
    (is (not (.exists (io/file dir))) "not even the directory")))

(deftest an-unwritable-directory-is-a-typed-error
  (let [blocked (io/file (temp-dir) "not-a-directory")]
    (spit blocked "")
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (qt/save-failure! diverged {:dir (str blocked)})))]
      (is (= :save-failed (:quint/error (ex-data e))))
      (is (str/includes? (ex-message e) ":save-failure false")
          "the message says how to turn the writing off")
      (is (instance? java.io.IOException (:cause (ex-data e)))))))

(deftest the-failure-message-names-the-file-it-just-wrote
  (let [saved (qt/save-failure! diverged {:dir (temp-dir)})
        s     (report/result-str saved)]
    (is (str/includes? s (get-in saved [:failure :saved])))
    (is (str/includes? s "saved"))))

(deftest a-spec-that-cannot-be-named-still-gets-a-file
  (let [dir   (temp-dir)
        saved (qt/save-failure! (dissoc diverged :cmd) {:dir dir})]
    (is (= (str (io/file dir "trace-seed42-trace3.itf.json"))
           (get-in saved [:failure :saved])))))

(deftest a-spec-given-by-path-does-not-nest-the-artifact
  (let [dir   (temp-dir)
        saved (qt/save-failure! (assoc diverged :cmd ["quint" "run" "spec/bank.qnt"])
                                {:dir dir})]
    (is (= (str (io/file dir "bank-seed42-trace3.itf.json"))
           (get-in saved [:failure :saved]))
        "the directory the spec lives in is not part of the name")))
