// Shared helpers for all pages.
async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
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


const REQ_STATUS = { SUBMITTED: 'Submitted', BUYER_REVIEW: 'With a buyer', APPROVED: 'Approved', POOLED: 'Waiting to be ordered',
  ORDERED: 'Ordered', REJECTED: 'Rejected', CLOSED: 'Closed', CANCELLED: 'Cancelled' };

/** An employee's requests with the outcome: rejection reason, what was bought, substitutions, PO numbers. */
function myRequestsHtml(list) {
  return '<div class="myreqs">' + list.map(r => {
    const s = r.sourced;
    const po = (r.history || []).map(h => (h.note || '').match(/PO-\d{4}-\d+/g)).flat().filter(Boolean);
    let outcome = '';
    if (r.status === 'REJECTED') outcome = `<div class="out bad">Rejected: ${esc(r.rejectionReason || '')}</div>`;
    else if (s) {
      outcome = `<div class="out">Buying: <b>${esc(s.description)}</b> from ${esc(s.supplierName)} · ${s.quantity} × ${money(s.unitPrice, r.currency)}${po.length ? ' · ' + esc(po.join(', ')) : ''}</div>`;
      if (s.substituted) outcome += `<div class="out warn">Changed from your choice (${esc(s.chosenModel)}): ${esc(s.substitutionReason)}</div>`;
      if (s.buyerNote) outcome += `<div class="out">Buyer's note: ${esc(s.buyerNote)}</div>`;
    } else if (r.status === 'BUYER_REVIEW') outcome = '<div class="out muted">A buyer is sourcing this.</div>';
    else if (po.length) outcome = `<div class="out">On purchase order ${esc(po.join(', '))}</div>`;
    const last = (r.history || []).slice(-1)[0];
    return `<div class="myreq"><div><b>${esc(r.number)}</b> · ${esc(r.title || r.categoryName)}
        <span class="rstatus ${r.status}">${REQ_STATUS[r.status] || r.status}</span></div>
        <div class="muted small">submitted ${esc((r.submittedAt || '').slice(0, 10))}${r.neededBy ? ' · needed by ' + esc(r.neededBy) : ''}${last ? ' · last update ' + esc((last.at || '').slice(0, 16).replace('T', ' ')) : ''}</div>${outcome}</div>`;
  }).join('') + '</div>';
}
