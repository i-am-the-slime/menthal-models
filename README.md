menthal-models
==============

This project contains the avro models for Menthal's Spark Cluster.

Usage with SBT (Scala)
=============

1.
If you do not have it already, add Bintray as a resolver to
`build.sbt`:
```
  resolvers += "jcenter" at "http://jcenter.bintray.com"
```
2.
Add the dependency by adding the following to your `build.sbt`:
```
  libraryDependencies += "org.menthal" % "menthal-models" % "0.2"
```

Running `sbt compile` will generate the classes.

Running `sbt publishLocal` creates a local dependency which can be pulled in by using

TODO
==============

Add some tests.
