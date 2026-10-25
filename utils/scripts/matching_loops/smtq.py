"""Quantifiers of an SMT-LIB file: patterns, bodies, Viper sources, and trigger feeding."""
from pathlib import Path
import re
from dataclasses import dataclass, field

TOKEN = re.compile(r'\n|[ \t\r]+|;[^\n]*|\|[^|]*\||"(?:[^"]|"")*"|\(|\)|[^\s()|";]+')
STRIP = re.compile(r'\|[^|]*\||"(?:[^"]|"")*"|;[^\n]*')
BINDERS = {'forall', 'exists'}
NOT_APPLICATIONS = BINDERS | {'let', '!', '_', 'as'}


class Node(list):
    line = 0


def parse(text, first_line=1):
    """Parses SMT-LIB text into nested Node lists of atom strings; each Node has a .line."""
    stack = [Node()]
    line = first_line
    for match in TOKEN.finditer(text):
        token = match.group()
        if token == '\n':
            line += 1
        elif token == '(':
            node = Node()
            node.line = line
            stack.append(node)
        elif token == ')':
            node = stack.pop()
            stack[-1].append(node)
        elif token[0] in ' \t\r;':
            continue
        else:
            line += token.count('\n')
            stack[-1].append(token[1:-1] if token[0] == '|' else token)
    return stack[0]


def show(term):
    if isinstance(term, list):
        return '(' + ' '.join(show(t) for t in term) + ')'
    return term


@dataclass
class Quantifier:
    qid: str
    line: int
    variables: list
    patterns: list
    body: object
    parent: str = None
    comment: str = ''
    weight: int = None
    copies: int = 1
    pattern_terms: list = field(default_factory=list)
    heads: set = field(default_factory=set)

    def __post_init__(self):
        for pattern in self.patterns:
            self.pattern_terms.append({show(sub) for term in pattern for sub in subterms(term)})
            for term in pattern:
                if isinstance(term, list) and term and isinstance(term[0], str):
                    self.heads.add(term[0])


def subterms(term):
    yield term
    if isinstance(term, list):
        for t in term[1:]:
            yield from subterms(t)


def quantified_commands(path):
    """Yields (first line, text, last comment) for each top-level command that contains a binder."""
    depth = 0
    start = 0
    lines = []
    comment = ''
    with open(path, errors='replace') as f:
        for number, line in enumerate(f, 1):
            if depth == 0:
                if line.startswith(';'):
                    if not line.startswith(';@'):
                        comment = line.strip()
                    continue
                start = number
                lines = []
            bare = STRIP.sub('', line)
            depth += bare.count('(') - bare.count(')')
            lines.append(line)
            if depth <= 0:
                depth = 0
                text = ''.join(lines)
                if 'forall' in text or 'exists' in text:
                    yield start, text, comment


def quantifiers(path):
    """All quantifiers in the file, including nested ones, keyed by the name Z3 reports."""
    found = {}
    for start, text, comment in quantified_commands(path):
        for command in parse(text, start):
            collect(command, None, comment, found)
    return found


def collect(term, parent, comment, found):
    if not isinstance(term, list):
        return
    if len(term) == 3 and term[0] in BINDERS:
        body = term[2]
        patterns, qid, weight = [], None, None
        if isinstance(body, list) and body and body[0] == '!':
            attributes = body[2:]
            for key, value in zip(attributes[::2], attributes[1::2]):
                if key == ':pattern':
                    patterns.append(list(value))
                elif key == ':qid':
                    qid = value
                elif key == ':weight':
                    weight = int(value)
            body = body[1]
        name = qid or f'k!{term.line}'
        variables = [v[0] for v in term[1] if isinstance(v, list)]
        if name in found:
            found[name].copies += 1
        else:
            found[name] = Quantifier(name, term.line, variables, patterns, body, parent, comment, weight)
        collect(body, name, comment, found)
        return
    for t in term:
        collect(t, parent, comment, found)


def applications(term, inside=False):
    """Function applications in term, not descending into nested binders."""
    if not isinstance(term, list) or not term:
        return
    head = term[0]
    if head in BINDERS:
        return
    if head == 'let':
        for binding in term[1]:
            yield from applications(binding[1])
        yield from applications(term[2])
        return
    if head == '!':
        yield from applications(term[1])
        return
    if isinstance(head, str) and head not in NOT_APPLICATIONS:
        yield term
    for t in term[1:]:
        yield from applications(t)


def atoms(term):
    if isinstance(term, list):
        for t in term:
            yield from atoms(t)
    else:
        yield term


def mentions(term, names):
    return any(t in names for t in subterms(term) if isinstance(t, str))


def feeding_terms(source, target):
    """Terms that instantiating source creates and that can match a trigger of target.

    A term feeds when its head is a trigger head of target, it mentions a bound variable of
    source, and some pattern of source can instantiate source without already containing it.
    """
    bound = set(source.variables)
    terms = []
    for app in applications(source.body):
        if app[0] in target.heads and mentions(app, bound):
            text = show(app)
            if text not in terms and any(text not in p for p in source.pattern_terms):
                terms.append(text)
    return terms


def trigger_cycles(quants):
    """Strongly connected components of the feeding graph with a cycle, as lists of qids."""
    names = [q.qid for q in quants]
    edges = {q.qid: [r.qid for r in quants if feeding_terms(q, r)] for q in quants}
    index, low, stack, on, result = {}, {}, [], set(), []

    def visit(v):
        index[v] = low[v] = len(index)
        stack.append(v)
        on.add(v)
        for w in edges[v]:
            if w not in index:
                visit(w)
                low[v] = min(low[v], low[w])
            elif w in on:
                low[v] = min(low[v], index[w])
        if low[v] == index[v]:
            component = []
            while True:
                w = stack.pop()
                on.discard(w)
                component.append(w)
                if w == v:
                    break
            if len(component) > 1 or v in edges[v]:
                result.append(sorted(component, key=names.index))

    for v in names:
        if v not in index:
            visit(v)
    return result


SOURCE = re.compile(r'([^\s|@]+\.vpr)@(\d+)@(\d+)')
GENERATED = [  # qid suffix or prefix -> what Silicon generates it for
    ('-invOfFct', 'quantified-permission inverse-function axiom'),
    ('-fctOfInv', 'quantified-permission inverse-function axiom'),
    ('-rcvrInj', 'quantified-permission receiver injectivity'),
    ('-permImpliesNonNull', 'quantified-permission non-null axiom'),
    ('qp.psmValDef', 'snapshot-map value definition'),
    ('qp.psmResTrgDef', 'snapshot-map trigger definition'),
    ('qp.psmDomDef', 'snapshot-map domain definition'),
    ('qp.smValDef', 'snapshot-map value definition'),
    ('qp.resTrgDef', 'snapshot-map trigger definition'),
    ('qp.resPrmSumDef', 'permission-sum definition'),
    ('qp.', 'quantified-permission axiom'),
    ('quant-u-', 'function axiom or other unnamed Silicon quantifier'),
]
QP_SYMBOL = re.compile(r'^(inv|img)@')
DOMAIN_AXIOM = re.compile(r'^(?:\$(.+?)_)?prog\.(.+)$')


def load_sources(vpr):
    """(path, text) for a .vpr file and the files it imports, transitively."""
    sources, todo, seen = [], [Path(vpr)], set()
    while todo:
        path = todo.pop(0).resolve()
        if path in seen or not path.exists():
            continue
        seen.add(path)
        text = path.read_text(errors='replace')
        sources.append((path, text))
        todo += [path.parent / m for m in re.findall(r'^\s*import\s+"([^"]+)"', text, re.M)]
    return sources


def find(pattern, sources):
    """'file:line' of the first match of pattern in sources, or None."""
    for path, text in sources or ():
        m = re.search(pattern, text)
        if m:
            return f'{path.name}:{text.count(chr(10), 0, m.start()) + 1}'
    return None


def describe(qid, quants, sources=None):
    """A short human description of where a quantifier comes from."""
    q = quants.get(qid)
    if qid.startswith('k!') and q is not None and q.parent:
        return f'unnamed quantifier nested in {q.parent}: {describe(q.parent, quants, sources)}'
    found = SOURCE.search(qid)
    if found:
        kind = 'user quantifier' if qid.startswith('prog.') else 'Silicon quantifier'
        return f'{kind} at {found.group(1).rsplit("/", 1)[-1]}:{found.group(2)}:{found.group(3)}'
    found = DOMAIN_AXIOM.match(qid)
    if found:
        domain, name = found.groups()
        where = ''
        if sources:
            at = find(r'axiom\s+' + re.escape(name) + r'\b', sources)
            where = f' at {at}' if at else ' (not in the sources: Silicon built-in theory)'
        return f'axiom {name}' + (f' of {domain}' if domain else '') + where
    if q is None:
        return 'not found in the replay file'
    kind = next((text for key, text in GENERATED if qid.endswith(key) or qid.startswith(key)), 'quantifier')
    if qid.endswith(('-invOfFct', '-fctOfInv', '-rcvrInj', '-permImpliesNonNull')):
        kind += f' for {qid.rsplit("-", 1)[0]}'
    heads = ', '.join(sorted(q.heads)) or 'no pattern'
    text = f'Silicon {kind}, triggers on {heads}'
    owner = qp_owner(q, quants, sources)
    if owner:
        text += f', from {owner}'
    elif qid.startswith('quant-u-'):
        text += viper_functions(q.heads, sources)
    if q.copies > 1:
        text += f' ({q.copies} copies)'
    if q.comment:
        text += f'; after "{q.comment[:100]}"'
    return text


def viper_functions(heads, sources):
    """' (function f at file:line)' for heads that name functions declared in the sources."""
    found = []
    for head in sorted({h.split('%')[0] for h in heads}):
        at = find(r'function\s+' + re.escape(head) + r'\s*\(', sources)
        if at:
            found.append(f'function {head} at {at}')
    return f' ({", ".join(found)})' if found else ''


def root_of(q, quants):
    while q.parent in quants:
        q = quants[q.parent]
    return q


def qp_owner(q, quants, sources=None):
    """Where the inverse or image functions of q come from: a positioned quantifier if one
    uses them, else the first other quantifier that does."""
    mine = {t for t in atoms([q.body, q.patterns]) if QP_SYMBOL.match(t)}
    if not mine:
        return None
    users = [o for o in quants.values() if o is not q and root_of(o, quants) is not q
             and any(t in mine for t in atoms(o.body))]
    for other in users:
        found = SOURCE.search(other.qid)
        if found:
            return f'{found.group(1).rsplit("/", 1)[-1]}:{found.group(2)}:{found.group(3)}'
    if users:
        root = root_of(users[0], quants)
        return (f'{root.qid} (triggers on {", ".join(sorted(root.heads)) or "no pattern"})'
                + viper_functions(root.heads, sources))
    return None
