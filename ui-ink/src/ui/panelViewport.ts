export interface PanelViewport {
  visibleLines: string[]
  totalLines: number
  scrollOffset: number
  endOffset: number
  pageSize: number
  maxOffset: number
}

export function buildPanelViewport(
  body: string,
  columns: number | undefined,
  rows: number | undefined,
  scrollOffset: number,
): PanelViewport {
  const wrappedLines = wrapPanelBody(body, panelContentWidth(columns))
  const pageSize = panelPageSize(rows)
  const maxOffset = Math.max(0, wrappedLines.length - pageSize)
  const safeOffset = clamp(scrollOffset, 0, maxOffset)
  const visibleLines = wrappedLines.slice(safeOffset, safeOffset + pageSize)
  const endOffset = Math.min(wrappedLines.length, safeOffset + visibleLines.length)

  return {
    visibleLines,
    totalLines: wrappedLines.length,
    scrollOffset: safeOffset,
    endOffset,
    pageSize,
    maxOffset,
  }
}

export function nextPanelScrollOffset(
  body: string,
  columns: number | undefined,
  rows: number | undefined,
  scrollOffset: number,
  direction: 'up' | 'down' | 'pageUp' | 'pageDown',
): number {
  const viewport = buildPanelViewport(body, columns, rows, scrollOffset)

  switch (direction) {
    case 'up':
      return clamp(viewport.scrollOffset - 1, 0, viewport.maxOffset)
    case 'down':
      return clamp(viewport.scrollOffset + 1, 0, viewport.maxOffset)
    case 'pageUp':
      return clamp(viewport.scrollOffset - viewport.pageSize, 0, viewport.maxOffset)
    case 'pageDown':
      return clamp(viewport.scrollOffset + viewport.pageSize, 0, viewport.maxOffset)
  }
}

function panelPageSize(rows: number | undefined): number {
  return Math.max(8, (rows ?? 24) - 14)
}

function panelContentWidth(columns: number | undefined): number {
  return Math.max(16, (columns ?? 120) - 4)
}

function wrapPanelBody(body: string, width: number): string[] {
  const logicalLines = body.split(/\r?\n/)
  const wrapped = logicalLines.flatMap((line) => wrapPanelLine(line, width))
  return wrapped.length > 0 ? wrapped : [' ']
}

function wrapPanelLine(line: string, width: number): string[] {
  if (width <= 0) {
    return [line || ' ']
  }
  if (line.length === 0) {
    return [' ']
  }

  const wrapped: string[] = []
  let remaining = line
  while (remaining.length > width) {
    wrapped.push(remaining.slice(0, width))
    remaining = remaining.slice(width)
  }
  wrapped.push(remaining)
  return wrapped
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
