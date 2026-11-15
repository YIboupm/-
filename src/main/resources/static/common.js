// Shared helpers for all pages.
async function api(method, path, body, staffId) {
  const headers = body ? { 'Content-Type': 'application/json' } : {};
  if (staffId) headers['X-Staff-Id'] = staffId; // demo identity; production: single sign-on
  const res = await fetch(path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new Error((data && data.error) || `${res.status} ${res.statusText}`);
  }
  return data;
}

function esc(value) {
  if (value === null || value === undefined) return '';
  return String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function money(value, currency = 'EUR') {
  if (value === null || value === undefined) return '';
  return Number(value).toLocaleString('en-GB', { style: 'currency', currency });
}

function toast(message) {
  let el = document.querySelector('.toast');
  if (!el) {
    el = document.createElement('div');
    el.className = 'toast';
    document.body.appendChild(el);
  }
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove('show'), 3000);
}

const SOURCE_LABEL = { STATED: 'Stated', INFERRED: 'Inferred by AI', CONTEXT: 'From profile' };


/** Researched product candidates (special requests): model, configuration, fit, trade-offs, price with source. */
function candidatesHtml(list) {
  if (!list || !list.length) return '';
  const host = u => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return u; } };
  const link = u => `<a href="${esc(u)}" target="_blank" rel="noopener noreferrer">${esc(host(u))}</a>`;
  return '<div class="cands">' + list.map((c, i) => `<div class="cand${c.preferred ? ' preferred' : ''}">
      <div class="cand-head"><span class="cand-model">${esc(c.model)}</span>
        ${c.preferred ? '<span class="tag must">Employee\'s choice</span>' : (i === 0 ? '<span class="tag pref">Recommended</span>' : '')}</div>
      <div class="cand-config">${esc(c.configuration)}</div>
      <div class="cand-row"><span class="k">Fits</span><span>${esc(c.fits)}</span></div>
      ${c.tradeOffs ? `<div class="cand-row"><span class="k">Trade-offs</span><span>${esc(c.tradeOffs)}</span></div>` : ''}
      <div class="cand-row"><span class="k">List price</span><span>${c.price ? esc(c.price) + ' · ' + link(c.priceSourceUrl) : '<span class="muted">not found in sources</span>'}</span></div>
      <div class="cand-row"><span class="k">Sources</span><span>${(c.sources || []).map(link).join(' · ')}</span></div>
    </div>`).join('') + '</div>';
}


const REQ_STATUS = { SUBMITTED: 'Submitted', PENDING_APPROVAL: 'Waiting for approval', BUYER_REVIEW: 'With a buyer',
  SOURCING: 'Being sourced', STRATEGIC_REVIEW: 'Strategic review', REDIRECTED: 'Use an existing agreement', APPROVED: 'Approved', POOLED: 'Waiting to be ordered',
  ORDERED: 'Ordered', REJECTED: 'Rejected', CLOSED: 'Closed', CANCELLED: 'Cancelled' };

/** An employee's requests with the outcome: rejection reason, what was bought, substitutions, PO numbers. */
function myRequestsHtml(list) {
  return '<div class="myreqs">' + list.map(r => {
    const s = r.sourced;
    const po = (r.history || []).map(h => (h.note || '').match(/PO-\d{4}-\d+/g)).flat().filter(Boolean);
    let outcome = '';
    if (r.status === 'REJECTED') outcome = `<div class="out bad">Rejected: ${esc(r.rejectionReason || '')}</div>`;
    else if (r.status === 'REDIRECTED') outcome = `<div class="out warn">Covered by an existing agreement: ${esc(r.rejectionReason || '')}</div>`;
    else if (s) {
      outcome = `<div class="out">Buying: <b>${esc(s.description)}</b> from ${esc(s.supplierName)} · ${s.quantity} × ${money(s.unitPrice, r.currency)}${po.length ? ' · ' + esc(po.join(', ')) : ''}</div>`;
      if (s.substituted) outcome += `<div class="out warn">Changed from your choice (${esc(s.chosenModel)}): ${esc(s.substitutionReason)}</div>`;
      if (s.buyerNote) outcome += `<div class="out">Buyer's note: ${esc(s.buyerNote)}</div>`;
    } else if (r.status === 'BUYER_REVIEW') outcome = '<div class="out muted">A buyer is sourcing this.</div>';
    if (r.strategic) outcome = `<div class="out">Strategic review (policy 7): ${esc(r.strategic.reasons.join('; '))}${r.strategic.note ? ' · category manager: ' + esc(r.strategic.note) : (r.status === 'STRATEGIC_REVIEW' ? ' · the category manager decides how it is sourced' : '')}</div>` + outcome;
    if (r.splitNote) outcome = `<div class="out warn">${esc(r.splitNote)}</div>` + outcome;
    outcome = approvalsHtml(r) + budgetLine(r) + outcome;
    if (['PENDING_APPROVAL', 'BUYER_REVIEW', 'SOURCING', 'STRATEGIC_REVIEW', 'APPROVED', 'POOLED'].includes(r.status)) {
      outcome += `<div class="out"><button class="small-btn" data-cancel="${esc(r.id)}" data-number="${esc(r.number)}">Cancel request</button></div>`;
    }
    else if (po.length) outcome = `<div class="out">On purchase order ${esc(po.join(', '))}</div>`;
    const last = (r.history || []).slice(-1)[0];
    return `<div class="myreq"><div><b>${esc(r.number)}</b> · ${esc(r.title || r.categoryName)}
        <span class="rstatus ${r.status}">${REQ_STATUS[r.status] || r.status}</span></div>
        <div class="muted small">submitted ${esc((r.submittedAt || '').slice(0, 10))}${r.neededBy ? ' · needed by ' + esc(r.neededBy) : ''}${last ? ' · last update ' + esc((last.at || '').slice(0, 16).replace('T', ' ')) : ''}</div>${outcome}</div>`;
  }).join('') + '</div>';
}


const ROLE_LABEL = { LINE_MANAGER: 'Line manager', DEPARTMENT_HEAD: 'Department head', FINANCE_CONTROLLER: 'Finance controller',
  CFO: 'CFO', IT_ASSET_MANAGER: 'IT asset manager', BUYER: 'Buyer', CATEGORY_MANAGER: 'Category manager' };

/** Who has approved, who is next: names and roles only, no budget figures. */
function approvalsHtml(r) {
  const steps = r.approvals || [];
  if (!steps.length) return '';
  const icon = s => s.status === 'APPROVED' ? '✓' : s.status === 'REJECTED' ? '✗' : s.status === 'ESCALATED' ? '⇢' : '…';
  return '<div class="chain">' + steps.map(s => `<span class="step ${s.status}" title="${esc(s.reason)}">${icon(s)} ${esc(s.approverName)}
      <span class="muted small">${esc(ROLE_LABEL[s.role] || s.role)}</span></span>`).join('<span class="arrow">→</span>') + '</div>';
}

/** The budget verdict as the employee sees it: a yes/no, never an amount. */
function budgetLine(r) {
  if (r.budgetCheck === 'OVER_BUDGET') return '<div class="out warn">Over your department\'s budget for this quarter: the Finance Controller decides.</div>';
  if (r.budgetCheck === 'WITHIN_BUDGET') return '<div class="out ok">Within your department\'s budget.</div>';
  return '';
}

function budgetBadge(verdict) {
  if (verdict === 'OVER_BUDGET') return '<div class="budget-badge over">Over your department\'s budget for this quarter. You can still submit it: the Finance Controller will decide.</div>';
  if (verdict === 'WITHIN_BUDGET') return '<div class="budget-badge ok">Within your department\'s budget</div>';
  if (verdict === 'NOT_DISCLOSED') return '<div class="budget-badge">Budget is checked when the request is submitted for approval</div>';
  return '';
}

/** Navigation shared by every page. */
function renderNav(active) {
  const pages = [['index.html', 'New request'], ['my-requests.html', 'My requests'], ['approvals.html', 'Approvals'],
    ['buyer.html', 'Buyer desk'], ['strategic.html', 'Strategic'], ['budget.html', 'Budget'], ['pool.html', 'Pool &amp; orders'], ['audit.html', 'Audit log']];
  const nav = document.querySelector('header nav');
  if (nav) nav.innerHTML = pages.map(([href, label]) => `<a href="${href}"${href === active ? ' class="active"' : ''}>${label}</a>`).join('');
}

/** "Cancel request" buttons rendered by myRequestsHtml: the requester withdraws; any budget held is released. */
function bindCancel(root, employeeId, reload) {
  root.querySelectorAll('[data-cancel]').forEach(b => b.onclick = async () => {
    const reason = prompt(`Cancel ${b.dataset.number}? Reason (optional):`, '');
    if (reason === null) return;
    try {
      await api('POST', `/api/requests/${b.dataset.cancel}/cancel`, { employeeId, reason });
      toast(`${b.dataset.number} cancelled`);
      await reload();
    } catch (e) { toast('Error: ' + e.message); }
  });
}


const BRIEF_CHECK_LABEL = { MET: 'Met', NOT_MET: 'Not met', UNKNOWN: 'Not in catalog' };

function daysUntil(d) {
  if (!d) return '';
  const days = Math.round((new Date(d) - new Date(new Date().toDateString())) / 86400000);
  return days < 0 ? ' · overdue' : ` · in ${days} day${days === 1 ? '' : 's'}`;
}

function requirementsOf(r) {
  const checks = r.requirementChecks || [];
  return r.requirements && r.requirements.length ? r.requirements : checks.map(k => k.requirement);
}

/** The request brief shared by the buyer desk and the strategic review: what, why, ruled out, conversation. */
function briefHtml(r) {
    const line = r.lines[0];
    const qty = line ? line.quantity : (r.attributes && r.attributes.quantity) || 1;
    const title = r.title || r.categoryName;
    // what to source: the employee's conditions; when a reference item exists, how it compares
    const checks = r.requirementChecks || [];
    const reqs = (r.requirements && r.requirements.length ? r.requirements : checks.map(k => k.requirement));
    const reqList = reqs.length ? '<ul class="reqs">' + reqs.map(q => {
        const k = checks.find(c => c.requirement.description === q.description);
        const ref = k ? `<span class="muted small">Reference item: ${esc(k.actual || 'not specified')}</span>
            <span class="check ${k.status}">${BRIEF_CHECK_LABEL[k.status]}</span>` : '';
        return `<li><span class="tag ${q.strength === 'preference' ? 'pref' : 'must'}">${q.strength === 'preference' ? 'Nice to have' : 'Must have'}</span>
          <span style="flex:1">${esc(q.description)}</span>${ref}</li>`;
      }).join('') + '</ul>'
      : '<div class="muted">No structured requirements recorded: read the conversation below.</div>';
    // ruled out: company resources / catalog items the agent considered, plus why the catalog does not fit
    const alts = (r.alternativesConsidered || []).map(a => `<li><span class="what">${esc(a.name)}</span>
        <span class="muted small"> · ${esc(a.kind)} ${esc(a.id)}</span><div class="why">${esc(a.whyNot)}</div></li>`);
    const catalogReasons = (r.specialRequirements || []).filter(s => !s.startsWith('Not using '))
      .map(s => `<li><span class="what">Standard catalog</span><div class="why">${esc(s)}</div></li>`);
    const ruled = alts.concat(catalogReasons);
    const words = (r.originalRequest || '').split('\n').filter(Boolean);

  return `      <div class="facts">
        <div class="fact"><div class="k">Quantity</div><div class="v">${esc(qty)}</div></div>
        <div class="fact"><div class="k">Needed by</div><div class="v">${esc(r.neededBy || 'not given')}<span class="muted small" style="font-weight:400">${daysUntil(r.neededBy)}</span></div></div>
        <div class="fact"><div class="k">Deliver to</div><div class="v">${esc(r.deliverySite || '')}</div></div>
        <div class="fact"><div class="k">Charge to</div><div class="v">${esc(r.chargeTo ? r.chargeTo.department + ' · ' + r.chargeTo.costCenter : r.requester.costCenter)}</div></div>
      </div>

      <div class="section-title">What to source</div>
      ${reqList}

      ${(r.suggestedModels || []).length ? `<div class="section-title">Suggested models · researched by the agent, discussed with the employee</div>
        ${candidatesHtml(r.suggestedModels)}
        <div class="muted small" style="margin-top:4px">List prices and specs come from the linked public pages; confirm price and availability with your supplier.</div>` : ''}

      ${r.mappingRationale ? `<div class="section-title">What it is for</div><div>${esc(r.mappingRationale)}</div>` : ''}

      ${ruled.length ? `<div class="section-title">Already ruled out</div><ul class="ruled">${ruled.join('')}</ul>` : ''}

      ${line ? `<div class="section-title">Closest standard item (reference only)</div>
        <div>${line.quantity} × ${esc(line.name)} <span class="muted">(${esc(line.sku)}, ${money(line.estimatedUnitPrice, r.currency)} each)</span></div>` : ''}

      <details class="convo"><summary>Conversation with the employee (${words.length} lines)</summary>
        <div class="quote">${esc(r.originalRequest || '(not recorded)')}</div></details>

`;
}
