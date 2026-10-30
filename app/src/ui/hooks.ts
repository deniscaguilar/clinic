import { useCallback, useEffect, useRef, useState } from 'react'
import { asProblem, type Problem } from '../api/errors'

export type Load<T> = { state: 'loading' } | { state: 'error'; problem: Problem } | { state: 'ready'; data: T }

/** Carrega dados ao montar e quando `deps` mudam; `reload` repete a chamada sem voltar ao estado de carregamento. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]): { load: Load<T>; reload: () => void } {
  const [state, setState] = useState<Load<T>>({ state: 'loading' })
  const [tick, setTick] = useState(0)
  const loader = useRef(load)
  loader.current = load
  useEffect(() => {
    let cancelled = false
    loader
      .current()
      .then((data) => !cancelled && setState({ state: 'ready', data }))
      .catch((error) => !cancelled && setState({ state: 'error', problem: asProblem(error) }))
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  return { load: state, reload: useCallback(() => setTick((n) => n + 1), []) }
}

/** Executa uma ação (envio de formulário, botão), guardando o erro para mostrar. `run` devolve se deu certo. */
export function useAction() {
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<Problem | null>(null)
  const run = useCallback(async (action: () => Promise<unknown>): Promise<boolean> => {
    setBusy(true)
    setProblem(null)
    try {
      await action()
      return true
    } catch (error) {
      setProblem(asProblem(error))
      return false
    } finally {
      setBusy(false)
    }
  }, [])
  return { busy, problem, run, clear: () => setProblem(null) }
}
