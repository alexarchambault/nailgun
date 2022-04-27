# Scala CLI fork of nailgun

This fork is based on the [Scala Center fork](https://github.com/scalacenter/nailgun), used by [Bloop](https://github.com/scalacenter/bloop). The fork here is intended to be used by [Scala CLI](https://github.com/VirtusLab/scala-cli), via
[bloop-core](https://github.com/scala-cli/bloop-core).

Compared to [`scalacenter/nailgun`](https://github.com/scalacenter/nailgun), it mainly:
- tweaks logging, so that some errors are not trapped, and stacktraces aren't unnecessarily printed in the console
