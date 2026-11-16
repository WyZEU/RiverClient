// The marker stays in the main process: object spreads retain it, JSON does not.
function createAuthSession() {
  const marker = Symbol("authSession");
  let generation = 0;
  let pendingRefresh = null;
  const isCurrent = (auth) => auth?.[marker] === generation;
  const assertCurrent = (auth) => {
    if (!isCurrent(auth)) throw Object.assign(new Error("Account changed. Try again."), { code: "AUTH_SESSION_CHANGED" });
  };
  return {
    tag: (auth) => ({ ...auth, [marker]: generation }),
    isTracked: (auth) => auth?.[marker] !== undefined,
    isCurrent,
    assertCurrent,
    invalidate() { generation++; pendingRefresh = null; },
    refresh(auth, operation) {
      assertCurrent(auth);
      if (pendingRefresh) return pendingRefresh;
      const task = Promise.resolve().then(() => { assertCurrent(auth); return operation(); });
      pendingRefresh = task;
      const clear = () => { if (pendingRefresh === task) pendingRefresh = null; };
      task.then(clear, clear);
      return task;
    }
  };
}

module.exports = { createAuthSession };
