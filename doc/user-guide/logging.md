## Logging

This chapter details how to inspect and modify the logs that Onyx produces.

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](http://doctoc.herokuapp.com/)*

- [File System](#file-system)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

### File System

By default, all Onyx output is logged to a file called `onyx.log` in the same directory as where the peer or coordinator jar is executing. The logging configuration can be overridden completely since it uses Timbre. Pass in the location of a configuration file for Timbre through the env config under the key `onyx.log/file`, or a Clojure-map configuration under `onyx.log/config`.
