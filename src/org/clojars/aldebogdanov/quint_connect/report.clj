(ns org.clojars.aldebogdanov.quint-connect.report
  "Result data -> string. A consumer of what replay returns, never a second
  source of truth about it."
  (:require [clojure.string :as str]))

(declare failure-str)

(defn- row [label v]
  (str "  " (format "%-9s" label) v))

(defn result-str
  "Render a `core/check` result: which trace of how many diverged, the failure
  itself, where the trace was saved, and the command that reproduces it. Nil
  when the result is `:ok?`."
  [{:keys [ok? seed traces cmd dir failure] :as result}]
  (when-not ok?
    (->> [(str "spec and implementation diverged on trace " (:trace failure)
               " of " traces ", seed " seed)
          (failure-str result)
          (when-let [saved (:saved failure)] (str "  saved      " saved))
          (when cmd (str "  reproduce  "
                         (when dir (str "cd " dir " && "))
                         (str/join " " cmd)))]
         (remove nil?)
         (str/join "\n"))))

(defn failure-str
  "Render the failure in a `replay/run-trace` result as a human-readable
  string, or nil when the result is `:ok?`.

  Names the step, the action and its picks, the handler var that diverged and
  the reader vars that observed it, the diverging part of both states, and —
  when a handler threw — the exception instead of a diff."
  [result]
  (when-let [{:keys [step action picks handler expected actual readers diff cause]}
             (:failure result)]
    (let [[only-spec only-app _] diff]
      (->> [(str "diverged at step " step (when action (str ", action " (pr-str action))))
            (when (seq picks)    (row "picks" (pr-str picks)))
            (when handler        (row "handler" handler))
            (when (seq readers)  (row "readers" (pr-str readers)))
            (if cause
              (row "threw" (str (.getName (class cause))
                                (when-let [m (ex-message cause)] (str ": " m))))
              (str (row "expected" (pr-str expected)) "\n"
                   (row "actual" (pr-str actual))))
            (when (and (nil? cause) (or only-spec only-app))
              (str (row "in spec" (pr-str only-spec)) "\n"
                   (row "in app" (pr-str only-app))))]
           (remove nil?)
           (str/join "\n")))))
