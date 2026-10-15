import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Loads data now and then every `intervalMs` (no polling if null). Returns the data, the last error and a
 * function to reload immediately, e.g. after an action.
 */
export function usePolling<T>(load: () => Promise<T>, intervalMs: number | null, deps: unknown[]) {
  const [data, setData] = useState<T | undefined>()
  const [error, setError] = useState<string | undefined>()
  const generation = useRef(0)

  const loadCallback = useCallback(load, deps)

  const reload = useCallback(async () => {
    const current = ++generation.current
    try {
      const result = await loadCallback()
      if (current === generation.current) {
        setData(result)
        setError(undefined)
      }
    } catch (e) {
      if (current === generation.current) setError(e instanceof Error ? e.message : String(e))
    }
  }, [loadCallback])

  // New source (e.g. another execution selected): drop the old data
  useEffect(() => {
    setData(undefined)
    setError(undefined)
  }, [loadCallback])

  useEffect(() => {
    reload()
    if (intervalMs == null) return
    const timer = setInterval(reload, intervalMs)
    return () => clearInterval(timer)
  }, [reload, intervalMs])

  return { data, error, reload }
}
