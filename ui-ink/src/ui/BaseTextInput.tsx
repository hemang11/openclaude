import React from 'react'
import { Box, Text } from 'ink'

import { renderPlaceholder } from '../hooks/renderPlaceholder.ts'
import { usePasteHandler } from '../input/usePasteHandler.ts'
import { useTerminalInput } from '../input/useTerminalInput.ts'
import type { BaseTextInputProps } from '../types/textInputTypes.ts'

export function BaseTextInput({
  value,
  placeholder,
  inputState,
  focus = true,
  showCursor = true,
  terminalFocus = true,
  dimColor = false,
  hidePlaceholderText = false,
  placeholderElement,
  onPaste,
  onIsPastingChange,
  argumentHint,
  inlineGhostText,
  children,
}: BaseTextInputProps): React.ReactElement {
  const { wrappedOnInput, isPasting } = usePasteHandler({
    onPaste,
    onInput: inputState.onInput,
  })

  React.useEffect(() => {
    onIsPastingChange?.(isPasting)
  }, [isPasting, onIsPastingChange])

  useTerminalInput(wrappedOnInput, { isActive: focus })

  const { showPlaceholder, renderedPlaceholder } = renderPlaceholder({
    placeholder,
    value,
    showCursor,
    focus,
    terminalFocus,
    hidePlaceholderText,
  })
  const renderedLines = inputState.renderedValue.length > 0 ? inputState.renderedValue.split('\n') : ['']
  const commandWithoutArgs =
    (value && value.trim().indexOf(' ') === -1) || (value && value.endsWith(' '))
  const showArgumentHint = Boolean(argumentHint && value && commandWithoutArgs && value.startsWith('/'))
  const visibleGhostText =
    inlineGhostText
    && inputState.cursorLine === renderedLines.length - 1
    && inputState.cursorColumn >= renderedLines[renderedLines.length - 1]!.length
      ? inlineGhostText
      : null

  return (
    <Box flexDirection="column">
      {showPlaceholder ? (
        placeholderElement ?? (
          <Text dimColor>
            {renderLine(renderedPlaceholder, showCursor && focus && terminalFocus ? 0 : null, null)}
          </Text>
        )
      ) : (
        <>
          {renderedLines.map((line, index) => (
            <Text key={`line-${index}`} dimColor={dimColor}>
              {renderLine(
                line,
                showCursor && focus && terminalFocus && index === inputState.cursorLine ? inputState.cursorColumn : null,
                index === renderedLines.length - 1 ? visibleGhostText : null,
              )}
            </Text>
          ))}
          {showArgumentHint ? <Text dimColor>{`${value.endsWith(' ') ? '' : ' '}${argumentHint}`}</Text> : null}
          {inputState.totalLines > inputState.visibleLineCount ? (
            <Text dimColor>
              {inputState.absoluteCursorLine + 1}/{inputState.totalLines} lines
            </Text>
          ) : null}
          {children}
        </>
      )}
    </Box>
  )
}

function renderLine(line: string, cursorColumn: number | null, ghostText: string | null): React.ReactNode {
  if (cursorColumn == null) {
    return (
      <>
        {line || ' '}
        {ghostText ? <Text dimColor>{ghostText}</Text> : null}
      </>
    )
  }

  const safeColumn = Math.max(0, Math.min(cursorColumn, line.length))
  const before = line.slice(0, safeColumn)
  const cursorCharacter = safeColumn < line.length ? line[safeColumn] : ' '
  const after = safeColumn < line.length ? line.slice(safeColumn + 1) : ''

  return (
    <>
      {before}
      <Text inverse>{cursorCharacter}</Text>
      {after}
      {ghostText ? <Text dimColor>{ghostText}</Text> : null}
    </>
  )
}
