const MIN_SAFE_ROWS = 24
const CLEAR_SCROLLBACK_ESCAPE = /\u001B\[3J/g

export function createInkStdout(stdout: NodeJS.WriteStream): NodeJS.WriteStream {
  return new Proxy(stdout, {
    get(target, property, receiver) {
      if (property === 'rows') {
        return Math.max(target.rows ?? 0, MIN_SAFE_ROWS)
      }
      if (property === 'write') {
        return (chunk: string | Uint8Array, ...args: unknown[]) => {
          const value = typeof chunk === 'string'
            ? chunk.replace(CLEAR_SCROLLBACK_ESCAPE, '')
            : Buffer.from(chunk).toString('utf8').replace(CLEAR_SCROLLBACK_ESCAPE, '')
          return Reflect.apply(target.write, target, [value, ...args])
        }
      }

      const value = Reflect.get(target, property, receiver)
      return typeof value === 'function' ? value.bind(target) : value
    },
  })
}
