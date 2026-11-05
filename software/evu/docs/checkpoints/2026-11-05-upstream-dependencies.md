# EVU upstream-dependency checkpoint: 2026-11-05

XSAI tag: `checkpoint/evu-upstream-deps-20261105`.
Repository: `qihangGuo/XSAI`; development branch: `feat/evu-optimization`.
Earlier checkpoint tags remain fixed.

## Dependency correction

The main `XSAI` checkout already pins the official upstream revisions below.
The external-vector checkout used local merge commits with exactly the same
Git trees. This checkpoint replaces only those gitlinks, not their contents:

| Dependency | Previous local merge | Official upstream revision |
| --- | --- | --- |
| XSAICache | `f8133554705899560be5f33a701a0ce4b5aae791` | `1186ab336ad130767f6e86fd6afc7cd1d24d1098` |
| utility | `3afcdfc17f6551b12250a283185dce2a8c661287` | `b3afd2908ec06ca58cc5f4ea1d3989ad3a4a5b03` |

The complete trees, including file modes and nested submodule revisions,
are identical for each pair:

```text
XSAICache tree: f0777febc1fd76e48993fe22ad77edb9d655b8c0
utility tree:   52ee6d2d68039a455c67c712dbac1369eaef5738
```

Both old/new commit diffs are empty. There are no additional EVU-specific
source changes in the local merge snapshots, so separate mirrors of these
two dependencies are unnecessary. Their tracked URLs remain
`OpenXiangShan/XSAICache` and `OpenXiangShan/Utility`.

The initialization helper no longer redirects either dependency to the XSAI
repository. It uses `git submodule sync -- XSAICache utility` to restore the
official URLs, including the submodule origins of existing initialized
clones. Only CUTE retains its existing personal mirror override because its
custom pinned commit still requires that mirror. CUTE and difftest gitlinks
are unchanged. Historical dependency backup tags are not deleted.

## Clone and initialize

```sh
git clone --branch checkpoint/evu-upstream-deps-20261105 \
  https://github.com/qihangGuo/XSAI.git XSAI_evu_checkpoint_20261105
cd XSAI_evu_checkpoint_20261105
bash software/evu/scripts/init_softmax_20261103_checkpoint.sh
make init
```

The helper keeps its existing filename for compatibility. Use the copy
shipped with the selected checkpoint; older checkpoint tags contain helpers
for their original gitlink revisions. Do not use recursive clone before the
helper configures the CUTE mirror.

## Functional pairing and validation

EVU/CPU RTL, guest software, CUTE, difftest and NEMU source are unchanged from
XSAI commit `4ae2f00ffe6d36aa5bea8b8f4762a6ccded90608`. The matching NEMU
revision remains `7f24031b90ddf68098990ef1d8ad9c690f3fe6a7`, available under its
existing `checkpoint/evu-flash-profile-145242-20261103` tag. No new NEMU tag or
source commit is needed for this dependency-only correction.

Validation consists of tree equality, empty source diffs, fetching the exact
commits from their respective official remotes, shell syntax and Git
whitespace checks. No RTL generation, simulator execution or binary build is
performed for this metadata-only checkpoint. Earlier performance measurements
remain historical server evidence, not newly executed tests.
