import React, { useMemo, useRef } from 'react'
import { Box, Text } from 'ink'
import { marked, type Token, type Tokens } from 'marked'

import { uiTheme } from './theme.ts'

type MarkdownProps = {
  text: string
  dimColor?: boolean
}

const TOKEN_CACHE_MAX = 500
const tokenCache = new Map<string, Token[]>()
const MD_SYNTAX_RE = /[#*`|[>\-_~]|\n\n|^\d+\. |\n\d+\. /m

marked.setOptions({
  gfm: true,
  breaks: false,
})

export function Markdown({ text, dimColor = false }: MarkdownProps): React.ReactElement {
  const tokens = useMemo(() => cachedLexer(stripPromptXmlTags(text)), [text])

  return (
    <Box flexDirection="column" gap={1}>
      {renderBlockTokens(tokens, dimColor, 'md')}
    </Box>
  )
}

export function StreamingMarkdown({ text, dimColor = false }: MarkdownProps): React.ReactElement {
  const stripped = stripPromptXmlTags(text)
  const stablePrefixRef = useRef('')

  if (!stripped.startsWith(stablePrefixRef.current)) {
    stablePrefixRef.current = ''
  }

  const boundary = stablePrefixRef.current.length
  const unstableTokens = marked.lexer(stripped.substring(boundary))

  let lastContentIndex = unstableTokens.length - 1
  while (lastContentIndex >= 0 && unstableTokens[lastContentIndex]?.type === 'space') {
    lastContentIndex -= 1
  }

  let advance = 0
  for (let index = 0; index < lastContentIndex; index += 1) {
    advance += unstableTokens[index]?.raw.length ?? 0
  }

  if (advance > 0) {
    stablePrefixRef.current = stripped.substring(0, boundary + advance)
  }

  const stablePrefix = stablePrefixRef.current
  const unstableSuffix = stripped.substring(stablePrefix.length)

  return (
    <Box flexDirection="column" gap={1}>
      {stablePrefix ? <Markdown text={stablePrefix} dimColor={dimColor} /> : null}
      {unstableSuffix ? <Markdown text={unstableSuffix} dimColor={dimColor} /> : null}
    </Box>
  )
}

export function InlineMarkdown({ text, dimColor = false }: MarkdownProps): React.ReactElement {
  const tokens = useMemo(() => marked.lexer(stripPromptXmlTags(text)), [text])
  const inlineTokens = firstInlineTokens(tokens, text)

  return (
    <Text dimColor={dimColor}>
      {renderInlineTokens(inlineTokens, dimColor, 'inline')}
    </Text>
  )
}

function cachedLexer(content: string): Token[] {
  if (!hasMarkdownSyntax(content)) {
    return [{
      type: 'paragraph',
      raw: content,
      text: content,
      tokens: [{
        type: 'text',
        raw: content,
        text: content,
      } as Token],
    } as Token]
  }

  const key = hashContent(content)
  const cached = tokenCache.get(key)
  if (cached) {
    tokenCache.delete(key)
    tokenCache.set(key, cached)
    return cached
  }

  const tokens = marked.lexer(content)
  if (tokenCache.size >= TOKEN_CACHE_MAX) {
    const first = tokenCache.keys().next().value
    if (first !== undefined) {
      tokenCache.delete(first)
    }
  }
  tokenCache.set(key, tokens)
  return tokens
}

function hasMarkdownSyntax(value: string): boolean {
  return MD_SYNTAX_RE.test(value.length > 500 ? value.slice(0, 500) : value)
}

function hashContent(value: string): string {
  let hash = 0
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash << 5) - hash) + value.charCodeAt(index)
    hash |= 0
  }
  return String(hash)
}

function stripPromptXmlTags(value: string): string {
  return value.replace(/<\/?(analysis|summary)>/gi, '')
}

function renderBlockTokens(tokens: Token[], dimColor: boolean, keyPrefix: string): React.ReactNode[] {
  const elements: React.ReactNode[] = []

  tokens.forEach((token, index) => {
    const key = `${keyPrefix}-${index}`
    switch (token.type) {
      case 'space':
        break
      case 'heading': {
        const heading = token as Tokens.Heading
        elements.push(
          <Text key={key} bold color={uiTheme.brand}>
            {inlineTokensToPlainText(heading.tokens)}
          </Text>,
        )
        break
      }
      case 'paragraph': {
        const paragraph = token as Tokens.Paragraph
        elements.push(
          <Text key={key} dimColor={dimColor}>
            {renderInlineTokens(paragraph.tokens, dimColor, key)}
          </Text>,
        )
        break
      }
      case 'text': {
        const textToken = token as Tokens.Text
        if (textToken.tokens && textToken.tokens.length > 0) {
          elements.push(
            <Text key={key} dimColor={dimColor}>
              {renderInlineTokens(textToken.tokens, dimColor, key)}
            </Text>,
          )
        } else {
          elements.push(
            <Text key={key} dimColor={dimColor}>
              {textToken.text}
            </Text>,
          )
        }
        break
      }
      case 'list':
        elements.push(renderListToken(token as Tokens.List, dimColor, key))
        break
      case 'blockquote':
        elements.push(renderBlockquoteToken(token as Tokens.Blockquote, dimColor, key))
        break
      case 'code':
        elements.push(renderCodeToken(token as Tokens.Code, key))
        break
      case 'table':
        elements.push(renderTableToken(token as Tokens.Table, dimColor, key))
        break
      case 'hr':
        elements.push(
          <Text key={key} color={uiTheme.brandMuted}>
            {'─'.repeat(40)}
          </Text>,
        )
        break
      default:
        if ('raw' in token && typeof token.raw === 'string' && token.raw.trim()) {
          elements.push(
            <Text key={key} dimColor={dimColor}>
              {token.raw.trim()}
            </Text>,
          )
        }
    }
  })

  return elements
}

function renderListToken(token: Tokens.List, dimColor: boolean, keyPrefix: string): React.ReactElement {
  return (
    <Box key={keyPrefix} flexDirection="column">
      {token.items.map((item, index) => (
        <Box key={`${keyPrefix}-${index}`} flexDirection="row" alignItems="flex-start">
          <Text color={uiTheme.brand}>
            {token.ordered ? `${index + 1}.` : '•'}
          </Text>
          <Box marginLeft={1} flexDirection="column" flexGrow={1}>
            {renderListItem(item, dimColor, `${keyPrefix}-${index}`)}
          </Box>
        </Box>
      ))}
    </Box>
  )
}

function renderListItem(item: Tokens.ListItem, dimColor: boolean, keyPrefix: string): React.ReactNode[] {
  if (!item.tokens || item.tokens.length === 0) {
    return [<Text key={keyPrefix} dimColor={dimColor}>{item.text}</Text>]
  }
  return renderBlockTokens(item.tokens, dimColor, keyPrefix)
}

function renderBlockquoteToken(
  token: Tokens.Blockquote,
  dimColor: boolean,
  keyPrefix: string,
): React.ReactElement {
  const children = renderBlockTokens(token.tokens, true, `${keyPrefix}-quote`)

  return (
    <Box key={keyPrefix} flexDirection="column">
      {children.map((child, index) => (
        <Box key={`${keyPrefix}-${index}`} flexDirection="row" alignItems="flex-start">
          <Text color={uiTheme.brandMuted}>│</Text>
          <Box marginLeft={1} flexDirection="column" flexGrow={1}>
            {child}
          </Box>
        </Box>
      ))}
    </Box>
  )
}

function renderCodeToken(token: Tokens.Code, keyPrefix: string): React.ReactElement {
  const lines = token.text.split(/\r?\n/)
  return (
    <Box key={keyPrefix} flexDirection="column" marginLeft={2}>
      {token.lang ? (
        <Text color={uiTheme.brandMuted} dimColor>
          {token.lang}
        </Text>
      ) : null}
      {lines.map((line, index) => (
        <Text
          key={`${keyPrefix}-${index}`}
          backgroundColor={uiTheme.codeFenceBackground}
          color={uiTheme.codeFenceForeground}
        >
          {line || ' '}
        </Text>
      ))}
    </Box>
  )
}

function renderTableToken(token: Tokens.Table, dimColor: boolean, keyPrefix: string): React.ReactElement {
  const headers = token.header.map((cell) => inlineTokensToPlainText(cell.tokens))
  const rows = token.rows.map((row) => row.map((cell) => inlineTokensToPlainText(cell.tokens)))
  const widths = computeColumnWidths(headers, rows)

  return (
    <Box key={keyPrefix} flexDirection="column">
      <Text color={uiTheme.brand}>{formatTableRow(headers, widths)}</Text>
      <Text color={uiTheme.brandMuted}>{formatTableDivider(widths)}</Text>
      {rows.map((row, index) => (
        <Text key={`${keyPrefix}-${index}`} dimColor={dimColor}>
          {formatTableRow(row, widths)}
        </Text>
      ))}
    </Box>
  )
}

function renderInlineTokens(tokens: Token[] | undefined, dimColor: boolean, keyPrefix: string): React.ReactNode[] {
  if (!tokens || tokens.length === 0) {
    return []
  }

  return tokens.map((token, index) => {
    const key = `${keyPrefix}-${index}`
    switch (token.type) {
      case 'text': {
        const textToken = token as Tokens.Text
        if (textToken.tokens && textToken.tokens.length > 0) {
          return (
            <React.Fragment key={key}>
              {renderInlineTokens(textToken.tokens, dimColor, key)}
            </React.Fragment>
          )
        }
        return <React.Fragment key={key}>{textToken.text}</React.Fragment>
      }
      case 'strong':
        return (
          <Text key={key} bold dimColor={dimColor}>
            {renderInlineTokens((token as Tokens.Strong).tokens, dimColor, key)}
          </Text>
        )
      case 'em':
        return (
          <Text key={key} italic dimColor={dimColor}>
            {renderInlineTokens((token as Tokens.Em).tokens, dimColor, key)}
          </Text>
        )
      case 'codespan':
        return (
          <Text
            key={key}
            backgroundColor={uiTheme.inlineCodeBackground}
            color={uiTheme.inlineCodeForeground}
          >
            {(token as Tokens.Codespan).text}
          </Text>
        )
      case 'link': {
        const link = token as Tokens.Link
        return (
          <React.Fragment key={key}>
            <Text color={uiTheme.brand} bold={!dimColor}>
              {renderInlineTokens(link.tokens, dimColor, `${key}-link`)}
            </Text>
            <Text dimColor={dimColor}>
              {' '}
              ({link.href})
            </Text>
          </React.Fragment>
        )
      }
      case 'del':
        return (
          <Text key={key} dimColor>
            ~{inlineTokensToPlainText((token as Tokens.Del).tokens)}~
          </Text>
        )
      case 'br':
        return <React.Fragment key={key}>{'\n'}</React.Fragment>
      default:
        return <React.Fragment key={key}>{'raw' in token ? token.raw : ''}</React.Fragment>
    }
  })
}

function inlineTokensToPlainText(tokens: Token[] | undefined): string {
  if (!tokens || tokens.length === 0) {
    return ''
  }

  return tokens.map((token) => {
    switch (token.type) {
      case 'text':
        return (token as Tokens.Text).tokens
          ? inlineTokensToPlainText((token as Tokens.Text).tokens)
          : (token as Tokens.Text).text
      case 'strong':
      case 'em':
      case 'del':
        return inlineTokensToPlainText((token as Tokens.Strong | Tokens.Em | Tokens.Del).tokens)
      case 'codespan':
        return (token as Tokens.Codespan).text
      case 'link':
        return inlineTokensToPlainText((token as Tokens.Link).tokens)
      case 'br':
        return '\n'
      default:
        return 'raw' in token ? token.raw : ''
    }
  }).join('')
}

function firstInlineTokens(tokens: Token[], fallback: string): Token[] {
  for (const token of tokens) {
    if (token.type === 'paragraph') {
      return (token as Tokens.Paragraph).tokens ?? [{
        type: 'text',
        raw: fallback,
        text: fallback,
      } as Token]
    }
    if (token.type === 'text' && (token as Tokens.Text).tokens) {
      return (token as Tokens.Text).tokens ?? [{
        type: 'text',
        raw: fallback,
        text: fallback,
      } as Token]
    }
  }
  return [{
    type: 'text',
    raw: fallback,
    text: fallback,
  } as Token]
}

function computeColumnWidths(headers: string[], rows: string[][]): number[] {
  return headers.map((header, index) => {
    const rowWidth = rows.reduce((width, row) => Math.max(width, visualWidth(row[index] ?? '')), 0)
    return Math.max(visualWidth(header), rowWidth, 3)
  })
}

function formatTableRow(row: string[], widths: number[]): string {
  return row
    .map((cell, index) => padCell(cell ?? '', widths[index] ?? 3))
    .join(' | ')
}

function formatTableDivider(widths: number[]): string {
  return widths.map((width) => '-'.repeat(Math.max(3, width))).join('-|-')
}

function padCell(value: string, width: number): string {
  const normalized = value.replace(/\s+/g, ' ').trim()
  const visual = visualWidth(normalized)
  if (visual >= width) {
    return normalized
  }
  return `${normalized}${' '.repeat(width - visual)}`
}

function visualWidth(value: string): number {
  return Array.from(value).length
}
