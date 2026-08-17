(ns build
  "Build and publish the library jar.

  `bb jar` writes target/quint-connect-<version>.jar, `bb install` puts it in
  the local ~/.m2 for testing a consumer, `bb deploy` pushes it to Clojars and
  needs CLOJARS_USERNAME and CLOJARS_PASSWORD in the environment — the latter a
  deploy token, never an account password.

  The version lives here and nowhere else. CHANGELOG.md records what is in it."
  (:require [clojure.tools.build.api :as b]))

(def lib 'org.clojars.aldebogdanov/quint-connect)
(def version "0.2.0")

(def ^:private class-dir "target/classes")
(def ^:private jar-file (format "target/%s-%s.jar" (name lib) version))
(def ^:private repo "https://github.com/aldebogdanov/quint-connect-clj")

(defn clean
  "Delete target/. Takes and returns nothing useful; tools.build calls it with
  a map."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Write the pom and the jar into target/. Source only — there is no AOT here
  and there should not be: the library is `.clj` files and a consumer compiles
  them itself."
  [_]
  (clean nil)
  (b/write-pom
   {:class-dir class-dir
    :lib       lib
    :version   version
    :basis     (b/create-basis {:project "deps.edn"})
    :src-dirs  ["src"]
    :scm       {:url                 repo
                :connection          (str "scm:git:git://github.com/"
                                          "aldebogdanov/quint-connect-clj.git")
                :developerConnection (str "scm:git:ssh://git@github.com/"
                                          "aldebogdanov/quint-connect-clj.git")
                :tag                 (str "v" version)}
    ;; Clojars shows the license on the artifact page, and a jar without one is
    ;; a jar nobody in a company may use.
    :pom-data  [[:description
                 "Model-based testing for Clojure, driven by Quint specifications."]
                [:url repo]
                [:licenses
                 [:license
                  [:name "Eclipse Public License 2.0"]
                  [:url "https://www.eclipse.org/legal/epl-2.0/"]]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "wrote" jar-file))

(defn install
  "Install the jar into the local ~/.m2, so a consumer can depend on the
  coordinate without anything being published."
  [_]
  (jar nil)
  (b/install {:basis     (b/create-basis {:project "deps.edn"})
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "installed" lib version "to ~/.m2"))

(defn deploy
  "Push the jar to Clojars. Reads CLOJARS_USERNAME and CLOJARS_PASSWORD from
  the environment; the password must be a deploy token, and a released version
  cannot be replaced afterwards."
  [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact  jar-file
    :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
