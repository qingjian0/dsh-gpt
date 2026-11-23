/** Remove DSH skill frontmatter before returning the instruction body. */
export function stripSkillFrontmatter(raw, source = 'bundled skill') {
  const text = String(raw)
  const match = /^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/u.exec(text)
  if (!match) throw new Error(source + ' has no valid YAML frontmatter')
  const content = text.slice(match[0].length).trim()
  if (!content) throw new Error(source + ' has an empty instruction body')
  return content
}
