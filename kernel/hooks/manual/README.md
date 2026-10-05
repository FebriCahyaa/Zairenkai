# ZKFC manual hooks

<!-- SPDX-License-Identifier: GPL-2.0-only -->

Use **manual** mode when:

- your kernel is built without `CONFIG_KPROBES`, or
- your kernel is **not** maintained in the Zairenkai source tree and you want
  hooks that never depend on symbol names.

Manual mode requires `CONFIG_ZKFC=y` (built-in), because core kernel code calls
into ZKFC. Hybrid mode (kprobes) needs no source changes and is the default.

## 1. Add the header

```sh
cp Zairenkai/kernel/hooks/manual/zkfc_hooks.h include/linux/zkfc_hooks.h
```

## 2. Exec hook (sulog of `su`)

Call `ZKFC_HOOK_EXEC()` right before the LSM check of the new binary, where
`bprm->filename` is final.

| Kernel     | File         | Function                            | Insert before                          |
|------------|--------------|-------------------------------------|----------------------------------------|
| 5.9 – 6.x  | `fs/exec.c`  | `bprm_execve()`                     | `retval = exec_binprm(bprm);`          |
| 4.14 – 5.8 | `fs/exec.c`  | `__do_execve_file()` / `do_execveat_common()` | `retval = exec_binprm(bprm);` |

```c
#include <linux/zkfc_hooks.h>
...
	ZKFC_HOOK_EXEC(bprm->filename);
	retval = exec_binprm(bprm);
```

## 3. New task hook (boost inheritance)

Call `ZKFC_HOOK_NEW_TASK()` at the start of `wake_up_new_task()`.

| Kernel     | File                  | Function             |
|------------|-----------------------|----------------------|
| 4.14 – 6.x | `kernel/sched/core.c` | `wake_up_new_task()` |

```c
#include <linux/zkfc_hooks.h>
...
void wake_up_new_task(struct task_struct *p)
{
	struct rq_flags rf;
	struct rq *rq;

	ZKFC_HOOK_NEW_TASK(p);
	...
```

`zkfc_on_new_task()` never sleeps and never takes scheduler locks; it only
queues the PID for a work item, so it is safe at this point.

## 4. Configure

```
CONFIG_ZKFC=y
CONFIG_ZKFC_HOOK_MANUAL=y
CONFIG_ZKFC_LICENSEE_TAG="<your tag>"
```

## 5. Verify

After boot, `dmesg | grep zkfc` must show `hook mode: manual`, and the
Zairenkai app shows **Hook: Manual** under *Sistem → ZKFC*.

## Porting to kernels not listed here

The two calls only need:

1. the final path of the program being executed, in process context, before
   the new image runs;
2. every newly created `task_struct`, after it is fully initialised and
   before it first runs.

Find the equivalent place in your tree and keep the macros. Do not call them
with spinlocks held that ZKFC could also take (`zkfc_on_exec` takes a ring
buffer spinlock with IRQs saved).
