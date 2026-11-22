(() => {
  'use strict';

  const SELECT_APPLET_APDU = '00A404000AA00000006203010C0601';

  const state = {
    status: null,
    summary: null,
    walletTx: [],
    auditLogs: [],
    activeTab: 'cards',
    searchQuery: '',
    isRefreshing: false,
    lastAvatarKey: '',
    pendingIssueAvatarBytes: null,
    pukTarget: null,
    deleteTargetId: null,
    toastTimer: null
  };

  const els = {
    readerBtn: document.getElementById('btnReaderReconnect'),
    readerDot: document.getElementById('readerDot'),
    readerNameText: document.getElementById('readerNameText'),
    toastBanner: document.getElementById('toastBanner'),
    toastMessage: document.getElementById('toastMessage'),
    toastCloseBtn: document.getElementById('toastCloseBtn'),

    heroAvatarImg: document.getElementById('heroAvatarImg'),
    heroAvatarInitials: document.getElementById('heroAvatarInitials'),
    heroStudentName: document.getElementById('heroStudentName'),
    heroCardStatusTag: document.getElementById('heroCardStatusTag'),
    heroStudentId: document.getElementById('heroStudentId'),
    heroClassName: document.getElementById('heroClassName'),
    heroDepartment: document.getElementById('heroDepartment'),
    heroDob: document.getElementById('heroDob'),
    heroRsaStatus: document.getElementById('heroRsaStatus'),
    heroWalletBalance: document.getElementById('heroWalletBalance'),
    heroPinTries: document.getElementById('heroPinTries'),
    btnTriggerPhone: document.getElementById('btnTriggerPhone'),

    searchFilter: document.getElementById('searchFilter'),
    btnOpenScrModal: document.getElementById('btnOpenScrModal'),
    btnExportJson: document.getElementById('btnExportJson'),
    btnOpenIssueModal: document.getElementById('btnOpenIssueModal'),

    tabButtons: document.querySelectorAll('.tab-btn'),
    panels: {
      cards: document.getElementById('panelCards'),
      wallet: document.getElementById('panelWallet'),
      attendance: document.getElementById('panelAttendance'),
      books: document.getElementById('panelBooks'),
      audit: document.getElementById('panelAudit')
    },
    tabCounts: {
      cards: document.getElementById('tabCountCards'),
      wallet: document.getElementById('tabCountWallet'),
      attendance: document.getElementById('tabCountAttendance'),
      books: document.getElementById('tabCountBooks'),
      audit: document.getElementById('tabCountAudit')
    },
    bodies: {
      cards: document.getElementById('cardsTableBody'),
      wallet: document.getElementById('walletTableBody'),
      attendance: document.getElementById('attendanceTableBody'),
      books: document.getElementById('booksTableBody'),
      audit: document.getElementById('auditTableBody')
    },

    apduForm: document.getElementById('apduForm'),
    apduHexInput: document.getElementById('apduHexInput'),
    apduLogScreen: document.getElementById('apduLogScreen'),
    btnClearApduLog: document.getElementById('btnClearApduLog'),

    dbTotalCards: document.getElementById('dbTotalCards'),
    dbSubCardsNote: document.getElementById('dbSubCardsNote'),
    dbTotalWallet: document.getElementById('dbTotalWallet'),
    dbTotalAttendance: document.getElementById('dbTotalAttendance'),
    dbBorrowedBooks: document.getElementById('dbBorrowedBooks'),
    dbTotalAudit: document.getElementById('dbTotalAudit'),
    lastSyncText: document.getElementById('lastSyncText'),
    btnManualRefresh: document.getElementById('btnManualRefresh'),

    issueCardForm: document.getElementById('issueCardForm'),
    issueFormError: document.getElementById('issueFormError'),
    issueAvatarFile: document.getElementById('issueAvatarFile'),
    issueAvatarPreview: document.getElementById('issueAvatarPreview'),
    issueAvatarPlaceholder: document.getElementById('issueAvatarPlaceholder'),
    issueAvatarInfo: document.getElementById('issueAvatarInfo'),
    btnSubmitIssueCard: document.getElementById('btnSubmitIssueCard'),

    pukForm: document.getElementById('pukForm'),
    pukFormError: document.getElementById('pukFormError'),
    pukDescription: document.getElementById('pukDescription'),
    pukCodeInput: document.getElementById('pukCodeInput'),
    btnSubmitPuk: document.getElementById('btnSubmitPuk'),

    scriptsListContainer: document.getElementById('scriptsListContainer'),
    customScrFile: document.getElementById('customScrFile'),

    deleteConfirmText: document.getElementById('deleteConfirmText'),
    btnConfirmDelete: document.getElementById('btnConfirmDelete')
  };

  function esc(val) {
    if (val === null || val === undefined) return '';
    return String(val)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function formatVnd(amount) {
    return (Number(amount) || 0).toLocaleString('vi-VN') + ' đ';
  }

  function formatTimestamp(tsSec) {
    if (!tsSec || Number(tsSec) <= 0) return '--';
    const d = new Date(Number(tsSec) * 1000);
    const pad = (n) => String(n).padStart(2, '0');
    return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  function getInitials(name, fallbackId) {
    const clean = (name || '').trim();
    if (!clean) return (fallbackId || 'SV').slice(0, 2).toUpperCase();
    const parts = clean.split(/\s+/);
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  function utf8ToHex(str) {
    const bytes = new TextEncoder().encode(str);
    return bytesToHex(bytes);
  }

  function bytesToHex(bytes) {
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0').toUpperCase()).join('');
  }

  function hexToUtf8(hex) {
    if (!hex || hex.length % 2 !== 0) return '';
    try {
      const bytes = new Uint8Array(hex.length / 2);
      for (let i = 0; i < hex.length; i += 2) {
        bytes[i / 2] = parseInt(hex.slice(i, i + 2), 16);
      }
      return new TextDecoder('utf-8', { fatal: false }).decode(bytes);
    } catch (_) {
      return '';
    }
  }

  function formatHexSpaced(hex) {
    return (hex || '').replace(/[^0-9A-Fa-f]/g, '').toUpperCase().replace(/(.{2})/g, '$1 ').trim();
  }

  function explainSw(sw) {
    const code = (sw || '').toUpperCase();
    if (code === '9000') return 'Thành công';
    if (code.startsWith('63C')) return `Sai mã PIN (còn ${parseInt(code.slice(3), 16)} lần thử)`;
    if (code === '6300') return 'Xác thực mã PUK thất bại';
    if (code === '6982') return 'Chưa xác thực PIN (Security status not satisfied)';
    if (code === '6983') return 'Thẻ đã bị khóa PIN (Cần mở khóa bằng PUK)';
    if (code === '6984') return 'Dữ liệu tham số không hợp lệ';
    if (code === '6985') return 'Điều kiện không thỏa mãn (Số dư không đủ)';
    if (code === '6A82') return 'Không tìm thấy Applet';
    if (code === '6A84') return 'Vượt quá giới hạn bộ nhớ hoặc hạn mức ví';
    if (code === '6D00') return 'Mã lệnh INS không được hỗ trợ';
    if (code === '6E00') return 'Mã lớp CLA không hợp lệ';
    if (code === '6700') return 'Sai độ dài Lc';
    return 'Phản hồi trạng thái thẻ';
  }

  function showToast(message, type = 'info') {
    if (state.toastTimer) clearTimeout(state.toastTimer);
    els.toastBanner.className = `toast-banner ${type}`;
    els.toastMessage.textContent = message;
    state.toastTimer = setTimeout(() => {
      els.toastBanner.classList.add('hidden');
    }, 4500);
  }

  function openModal(id) {
    const el = document.getElementById(id);
    if (el) el.classList.remove('hidden');
  }

  function closeModal(id) {
    const el = document.getElementById(id);
    if (el) el.classList.add('hidden');
  }

  async function apiJson(url, options) {
    const resp = await fetch(url, options);
    const text = await resp.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : {};
    } catch (_) {
      data = { raw: text };
    }
    if (!resp.ok) {
      throw new Error(data.message || data.error || `HTTP ${resp.status}`);
    }
    return data;
  }

  function appendApduLog(cmdHex, respDataHex, sw, timeMs, note) {
    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const timeStr = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
    const swUpper = (sw || '----').toUpperCase();

    let swClass = 'log-sw-err';
    if (swUpper === '9000') swClass = 'log-sw-ok';
    else if (swUpper.startsWith('63C')) swClass = 'log-sw-warn';

    let decodedPreview = '';
    const compactCmd = (cmdHex || '').replace(/\s+/g, '').toUpperCase();
    if (compactCmd.startsWith('802100') && respDataHex) {
      const txt = hexToUtf8(respDataHex).replace(/\0/g, '').trim();
      if (txt) decodedPreview = `"${txt}"`;
    }

    const entry = document.createElement('div');
    entry.className = 'log-entry';
    entry.innerHTML = `
      <div class="log-line">
        <span class="log-time">[${timeStr}]</span>
        <span>&gt;&gt;</span>
        <span class="log-tx">${esc(formatHexSpaced(cmdHex))}</span>
        ${note ? `<span class="log-time">(${esc(note)})</span>` : ''}
      </div>
      <div class="log-line">
        <span class="log-time">[${timeStr}]</span>
        <span>&lt;&lt;</span>
        ${decodedPreview ? `<span class="log-data">${esc(decodedPreview)}</span>` : (respDataHex ? `<span class="log-data">DATA[${respDataHex.length / 2}B]: ${esc(respDataHex.slice(0, 64))}${respDataHex.length > 64 ? '...' : ''}</span>` : '')}
        <span class="${swClass}">SW=${esc(swUpper)} (${esc(explainSw(swUpper))})</span>
        ${timeMs !== undefined ? `<span class="log-time">• ${timeMs}ms</span>` : ''}
      </div>
    `;
    els.apduLogScreen.appendChild(entry);
    while (els.apduLogScreen.children.length > 80) {
      els.apduLogScreen.removeChild(els.apduLogScreen.firstChild);
    }
    els.apduLogScreen.scrollTop = els.apduLogScreen.scrollHeight;
  }

  async function sendRawApdu(hexCmd, note = '') {
    const cleanHex = (hexCmd || '').replace(/[^0-9A-Fa-f]/g, '').toUpperCase();
    if (cleanHex.length < 8 || cleanHex.length % 2 !== 0) {
      throw new Error('Lệnh APDU Hex không hợp lệ (cần ít nhất 4 byte chẵn ký tự Hex).');
    }
    const res = await apiJson('/api/apdu', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apdu: cleanHex })
    });
    appendApduLog(res.command || cleanHex, res.data || '', res.sw || '----', res.timeMs, note);
    return res;
  }

  async function activateCard(studentId, pipeInfo, pin, triggerMessage) {
    const res = await apiJson('/api/activate-card', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        studentId,
        studentInfo: pipeInfo,
        pin: pin || '123456'
      })
    });
    state.lastAvatarKey = '';
    if (triggerMessage) {
      showToast(triggerMessage, 'success');
    }
    await refreshAll();
    return res;
  }

  function renderHeaderAndHero() {
    const st = state.status;
    const summary = state.summary;
    if (!st) return;

    // Top Nav Reader Badge
    els.readerNameText.textContent = st.reader || 'Virtual-JCIDE-Fallback-Engine';
    els.readerDot.className = `status-dot ${st.pcscConnected ? 'pcsc' : 'virtual'}`;

    const activeId = st.activeStudentId || '--';
    const cards = (summary && summary.cards) ? summary.cards : [];
    const activeRecord = cards.find((c) => c.studentId === activeId);

    const pipeParts = (st.activeStudentInfo || '').split('|');
    const fullName = activeRecord ? activeRecord.fullName : (pipeParts[1] || 'Chưa xác định');
    const dob = activeRecord ? activeRecord.dob : (pipeParts[2] || '--/--/----');
    const className = activeRecord ? activeRecord.className : (pipeParts[3] || '--');
    const department = activeRecord ? activeRecord.department : (pipeParts[4] || '--');
    const walletBalance = st.walletBalance !== undefined ? st.walletBalance : (activeRecord ? activeRecord.walletBalance : 500000);
    const pinTries = st.pinTries !== undefined ? st.pinTries : 3;
    const isLocked = Boolean(st.cardLocked || pinTries === 0 || (activeRecord && activeRecord.cardLocked));

    els.heroStudentName.textContent = fullName;
    els.heroStudentId.textContent = activeId;
    els.heroClassName.textContent = className;
    els.heroDepartment.textContent = department;
    els.heroDob.textContent = dob;

    const hasRsa = activeRecord && activeRecord.publicKeyModulusHex && activeRecord.publicKeyModulusHex.length > 8;
    els.heroRsaStatus.textContent = hasRsa ? 'RSA-1024 (Đã lưu DB)' : 'RSA-1024 (Trên chip)';

    if (isLocked) {
      els.heroCardStatusTag.textContent = 'Đã khóa PIN';
      els.heroCardStatusTag.className = 'hero-tag locked';
    } else {
      els.heroCardStatusTag.textContent = 'Đang trên đầu đọc';
      els.heroCardStatusTag.className = 'hero-tag ready';
    }

    els.heroWalletBalance.textContent = formatVnd(walletBalance);
    els.heroPinTries.innerHTML = `${pinTries}/3 <span style="font-size: 12px; font-weight: 400; color: var(--text-muted);">lần</span>`;
    els.heroPinTries.className = 'stat-number font-mono ' + (pinTries === 0 ? 'danger' : (pinTries < 3 ? 'warning' : ''));

    // Avatar reload only when active student or avatar flag changes
    els.heroAvatarInitials.textContent = getInitials(fullName, activeId);
    const avatarKey = `${activeId}:${Boolean(st.hasAvatar)}`;
    if (avatarKey !== state.lastAvatarKey) {
      state.lastAvatarKey = avatarKey;
      if (activeId && activeId !== '--') {
        els.heroAvatarImg.onload = () => {
          els.heroAvatarImg.classList.remove('hidden');
          els.heroAvatarInitials.classList.add('hidden');
        };
        els.heroAvatarImg.onerror = () => {
          els.heroAvatarImg.classList.add('hidden');
          els.heroAvatarInitials.classList.remove('hidden');
        };
        els.heroAvatarImg.src = `/api/cards/avatar?studentId=${encodeURIComponent(activeId)}&t=${Date.now()}`;
      } else {
        els.heroAvatarImg.classList.add('hidden');
        els.heroAvatarInitials.classList.remove('hidden');
      }
    }
  }

  function renderSummaryPanel() {
    const d = state.summary;
    if (!d) return;

    const fmt2 = (n) => String(n || 0).padStart(2, '0');
    els.dbTotalCards.textContent = fmt2(d.totalCards);
    els.dbSubCardsNote.textContent = d.lockedCards > 0
      ? `${d.lockedCards} thẻ đang khóa PIN • Khóa công khai RSA`
      : 'Định danh sinh viên & khóa công khai RSA';
    els.dbTotalWallet.textContent = fmt2(d.totalWalletTx !== undefined ? d.totalWalletTx : state.walletTx.length);
    els.dbTotalAttendance.textContent = fmt2(d.totalAttendance);
    els.dbBorrowedBooks.textContent = fmt2(d.borrowedBooks);
    els.dbTotalAudit.textContent = fmt2(d.totalAuditLogs);

    els.tabCounts.cards.textContent = (d.cards || []).length;
    els.tabCounts.wallet.textContent = state.walletTx.length;
    els.tabCounts.attendance.textContent = (d.attendance || []).length;
    els.tabCounts.books.textContent = (d.books || []).length;
    els.tabCounts.audit.textContent = state.auditLogs.length;
  }

  function matchesFilter(...fields) {
    const q = state.searchQuery.trim().toLowerCase();
    if (!q) return true;
    return fields.some((f) => String(f || '').toLowerCase().includes(q));
  }

  function renderActiveTable() {
    const d = state.summary;
    const activeId = state.status ? state.status.activeStudentId : '';

    if (state.activeTab === 'cards') {
      const rows = (d && d.cards ? d.cards : []).filter((c) =>
        matchesFilter(c.studentId, c.fullName, c.className, c.department)
      );
      if (!rows.length) {
        els.bodies.cards.innerHTML = '<tr><td colspan="8" class="empty-state-cell">Không tìm thấy hồ sơ thẻ phù hợp.</td></tr>';
        return;
      }
      els.bodies.cards.innerHTML = rows.map((c) => {
        const isActive = c.studentId === activeId;
        const isLocked = Boolean(c.cardLocked);
        const pinLabel = isLocked
          ? '<span class="status-badge locked">Đã khóa PIN</span>'
          : (isActive
            ? `<span class="status-badge ready">&#9679; Đang hoạt động</span>`
            : '<span class="status-badge ready">3 lần thử</span>');

        const pukBtn = isLocked
          ? `<button class="btn-action-outline btn-table btn-table-warn" data-action="puk-unlock" data-sid="${esc(c.studentId)}">Mở PUK</button>`
          : `<button class="btn-action-outline btn-table" data-action="puk-lock" data-sid="${esc(c.studentId)}">Khóa PUK</button>`;

        const mountBtn = isActive
          ? `<button class="btn-action-outline btn-table btn-table-active" data-action="activate" data-sid="${esc(c.studentId)}">Đang gắn</button>`
          : `<button class="btn-action-outline btn-table" data-action="activate" data-sid="${esc(c.studentId)}">Nạp thẻ</button>`;

        return `
          <tr class="${isActive ? 'active-student-row' : ''}">
            <td class="font-mono" style="font-weight: 600; color: ${isActive ? 'var(--brand-primary)' : 'var(--text-primary)'};">${esc(c.studentId)}</td>
            <td class="user-name-cell">${esc(c.fullName)}</td>
            <td>${esc(c.className)}</td>
            <td>${esc(c.department)}</td>
            <td class="font-mono text-right" style="font-weight: 500;">${esc(formatVnd(c.walletBalance ?? 500000))}</td>
            <td class="font-mono text-center">${esc(c.trainingScore)} / 100</td>
            <td class="text-center">${pinLabel}</td>
            <td>
              <div class="table-actions">
                ${mountBtn}
                ${pukBtn}
                <button class="btn-action-outline btn-table btn-table-danger" data-action="delete" data-sid="${esc(c.studentId)}">Xóa</button>
              </div>
            </td>
          </tr>
        `;
      }).join('');
    }

    if (state.activeTab === 'wallet') {
      const rows = (state.walletTx || []).filter((tx) =>
        matchesFilter(tx.txRef, tx.studentId, tx.txType, tx.counterparty, tx.description)
      );
      if (!rows.length) {
        els.bodies.wallet.innerHTML = '<tr><td colspan="8" class="empty-state-cell">Chưa có giao dịch ví điện tử nào phù hợp.</td></tr>';
        return;
      }
      els.bodies.wallet.innerHTML = rows.map((tx) => {
        const isCredit = String(tx.txType).toUpperCase() === 'CREDIT';
        const typeBadge = isCredit
          ? '<span class="status-badge ready">NẠP TIỀN</span>'
          : '<span class="status-badge warn">CHI TIÊU</span>';
        const rsaBadge = tx.verifiedByServer
          ? '<span class="status-badge ready">Hợp lệ</span>'
          : '<span class="status-badge neutral">Chưa k.tra</span>';
        const sign = isCredit ? '+' : '-';
        return `
          <tr>
            <td class="font-mono">${esc(tx.txRef || ('#' + tx.id))}</td>
            <td class="font-mono" style="font-weight: 500; color: var(--text-primary);">${esc(tx.studentId)}</td>
            <td>${typeBadge}</td>
            <td class="font-mono text-right" style="font-weight: 600; color: ${isCredit ? 'var(--success-text)' : 'var(--text-primary)'};">${sign}${esc(formatVnd(tx.amount))}</td>
            <td class="font-mono text-right">${esc(formatVnd(tx.balanceAfter))}</td>
            <td>
              <div style="color: var(--text-primary); font-weight: 500;">${esc(tx.description)}</div>
              <div style="font-size: 11px; color: var(--text-muted);">${esc(tx.counterparty)}</div>
            </td>
            <td class="text-center">${rsaBadge}</td>
            <td class="font-mono text-right" style="font-size: 11px;">${esc(formatTimestamp(tx.timestampSeconds))}</td>
          </tr>
        `;
      }).join('');
    }

    if (state.activeTab === 'attendance') {
      const rows = (d && d.attendance ? d.attendance : []).filter((a) =>
        matchesFilter(a.studentId, a.courseCode, a.courseName)
      );
      if (!rows.length) {
        els.bodies.attendance.innerHTML = '<tr><td colspan="6" class="empty-state-cell">Chưa có bản ghi điểm danh nào phù hợp.</td></tr>';
        return;
      }
      els.bodies.attendance.innerHTML = rows.map((a) => `
        <tr>
          <td class="font-mono" style="font-weight: 600; color: var(--text-primary);">${esc(a.studentId)}</td>
          <td class="font-mono">${esc(a.courseCode)}</td>
          <td class="user-name-cell">${esc(a.courseName)}</td>
          <td class="font-mono" style="font-size: 11px; color: var(--text-muted);">${esc((a.rsaSignatureHex || '').slice(0, 28))}${a.rsaSignatureHex && a.rsaSignatureHex.length > 28 ? '...' : ''}</td>
          <td class="text-center">${a.verifiedByServer ? '<span class="status-badge ready">RSA Hợp lệ</span>' : '<span class="status-badge warn">Chưa xác thực</span>'}</td>
          <td class="font-mono text-right" style="font-size: 11px;">${esc(formatTimestamp(a.timestampSeconds))}</td>
        </tr>
      `).join('');
    }

    if (state.activeTab === 'books') {
      const rows = (d && d.books ? d.books : []).filter((b) =>
        matchesFilter(b.bookCode, b.bookTitle, b.author, b.studentId)
      );
      if (!rows.length) {
        els.bodies.books.innerHTML = '<tr><td colspan="6" class="empty-state-cell">Không có đầu sách nào phù hợp.</td></tr>';
        return;
      }
      els.bodies.books.innerHTML = rows.map((b) => `
        <tr>
          <td class="font-mono" style="font-weight: 500;">0x${Number(b.bookCode).toString(16).toUpperCase().padStart(4, '0')}</td>
          <td class="user-name-cell">${esc(b.bookTitle)}</td>
          <td>${esc(b.author)}</td>
          <td class="font-mono" style="color: var(--text-primary);">${esc(b.studentId || '--')}</td>
          <td class="text-center">${b.borrowed ? '<span class="status-badge warn">Đang mượn</span>' : '<span class="status-badge ready">Sẵn sàng</span>'}</td>
          <td class="font-mono text-right" style="font-size: 11px;">${esc(formatTimestamp(b.timestampSeconds))}</td>
        </tr>
      `).join('');
    }

    if (state.activeTab === 'audit') {
      const rows = (state.auditLogs || []).filter((log) =>
        matchesFilter(log.studentId, log.actionType, log.description)
      );
      if (!rows.length) {
        els.bodies.audit.innerHTML = '<tr><td colspan="6" class="empty-state-cell">Chưa có nhật ký kiểm toán nào phù hợp.</td></tr>';
        return;
      }
      els.bodies.audit.innerHTML = rows.map((log) => `
        <tr>
          <td class="font-mono" style="color: var(--text-muted);">#${esc(log.id)}</td>
          <td class="font-mono" style="font-weight: 500; color: var(--text-primary);">${esc(log.studentId)}</td>
          <td><span class="status-badge neutral font-mono">${esc(log.actionType)}</span></td>
          <td class="font-mono">0x${Number(log.itemCode || 0).toString(16).toUpperCase().padStart(4, '0')}</td>
          <td>${esc(log.description)}</td>
          <td class="font-mono text-right" style="font-size: 11px;">${esc(formatTimestamp(log.timestampSeconds))}</td>
        </tr>
      `).join('');
    }
  }

  async function refreshAll() {
    if (state.isRefreshing) return;
    state.isRefreshing = true;
    try {
      const [status, summary, walletTx, auditLogs] = await Promise.all([
        apiJson('/api/status'),
        apiJson('/api/db-summary'),
        apiJson('/api/wallet'),
        apiJson('/api/audit-logs?limit=150')
      ]);
      state.status = status;
      state.summary = summary;
      state.walletTx = Array.isArray(walletTx) ? walletTx : [];
      state.auditLogs = Array.isArray(auditLogs) ? auditLogs : [];

      renderHeaderAndHero();
      renderSummaryPanel();
      renderActiveTable();

      const now = new Date();
      const pad = (n) => String(n).padStart(2, '0');
      els.lastSyncText.textContent = `Cập nhật lúc ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
    } catch (err) {
      els.readerDot.className = 'status-dot offline';
      els.readerNameText.textContent = 'Mất kết nối BridgeServer';
    } finally {
      state.isRefreshing = false;
    }
  }

  function switchTab(tabName) {
    state.activeTab = tabName;
    els.tabButtons.forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.tab === tabName);
    });
    Object.entries(els.panels).forEach(([key, panel]) => {
      panel.classList.toggle('hidden', key !== tabName);
    });
    renderActiveTable();
  }

  // Compress selected image on offscreen canvas to WebP (150x150, <= 7.8KB)
  async function compressImageToWebpBytes(file) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      const reader = new FileReader();
      reader.onload = (e) => {
        img.onload = () => {
          const size = 150;
          const canvas = document.createElement('canvas');
          canvas.width = size;
          canvas.height = size;
          const ctx = canvas.getContext('2d');

          const minSide = Math.min(img.width, img.height);
          const sx = (img.width - minSide) / 2;
          const sy = (img.height - minSide) / 2;
          ctx.drawImage(img, sx, sy, minSide, minSide, 0, 0, size, size);

          const tryQuality = (quality) => {
            canvas.toBlob((blob) => {
              if (!blob) {
                reject(new Error('Trình duyệt không hỗ trợ nén ảnh WebP.'));
                return;
              }
              if (blob.size > 7800 && quality > 0.3) {
                tryQuality(quality - 0.15);
                return;
              }
              blob.arrayBuffer().then((buf) => resolve(new Uint8Array(buf)));
            }, 'image/webp', quality);
          };
          tryQuality(0.75);
        };
        img.onerror = () => reject(new Error('Không thể đọc tệp ảnh đã chọn.'));
        img.src = e.target.result;
      };
      reader.onerror = () => reject(new Error('Lỗi đọc tệp ảnh.'));
      reader.readAsDataURL(file);
    });
  }

  async function handleIssueCardSubmit(e) {
    e.preventDefault();
    els.issueFormError.classList.add('hidden');

    const studentId = document.getElementById('issueStudentId').value.trim();
    const className = document.getElementById('issueClassName').value.trim();
    const fullName = document.getElementById('issueFullName').value.trim();
    const dob = document.getElementById('issueDob').value.trim();
    const department = document.getElementById('issueDepartment').value.trim();
    const defaultPin = document.getElementById('issuePin').value.trim();
    const walletBalance = parseInt(document.getElementById('issueBalance').value || '500000', 10);

    if (!/^[a-zA-Z0-9_-]{4,20}$/.test(studentId)) {
      els.issueFormError.textContent = 'MSSV phải từ 4–20 ký tự (chữ, số, gạch dưới hoặc gạch nối).';
      els.issueFormError.classList.remove('hidden');
      return;
    }
    if (fullName.length < 2 || fullName.includes('|') || /\d/.test(fullName)) {
      els.issueFormError.textContent = 'Họ và tên không hợp lệ (không chứa số hoặc ký tự "|").';
      els.issueFormError.classList.remove('hidden');
      return;
    }
    if (!/^\d{2}\/\d{2}\/\d{4}$/.test(dob)) {
      els.issueFormError.textContent = 'Ngày sinh cần nhập đúng định dạng dd/mm/yyyy.';
      els.issueFormError.classList.remove('hidden');
      return;
    }
    if (!/^\d{4,8}$/.test(defaultPin)) {
      els.issueFormError.textContent = 'Mã PIN phải gồm 4–8 chữ số.';
      els.issueFormError.classList.remove('hidden');
      return;
    }

    const pipeInfo = `${studentId}|${fullName}|${dob}|${className}|${department}`;
    const infoHex = utf8ToHex(pipeInfo);
    if (infoHex.length / 2 > 240) {
      els.issueFormError.textContent = 'Chuỗi thông tin sinh viên vượt quá giới hạn 240 bytes Short APDU.';
      els.issueFormError.classList.remove('hidden');
      return;
    }

    els.btnSubmitIssueCard.disabled = true;
    els.btnSubmitIssueCard.textContent = 'Đang ghi thẻ...';

    try {
      await apiJson('/api/activate-card', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ studentId, studentInfo: pipeInfo, pin: defaultPin })
      });

      await sendRawApdu(SELECT_APPLET_APDU, `Phát hành ${studentId}: Select Applet`);
      const pinHex = utf8ToHex(defaultPin);
      const pinLc = (pinHex.length / 2).toString(16).padStart(2, '0').toUpperCase();
      await sendRawApdu(`80100000${pinLc}${pinHex}`, `Phát hành ${studentId}: Verify PIN`);

      const infoLc = (infoHex.length / 2).toString(16).padStart(2, '0').toUpperCase();
      await sendRawApdu(`80200000${infoLc}${infoHex}`, `Phát hành ${studentId}: Ghi AES EEPROM`);

      const modResp = await sendRawApdu('8013000000', `Phát hành ${studentId}: Sinh RSA-1024`);
      const expResp = await sendRawApdu('8013020000', `Phát hành ${studentId}: Đọc Exponent E`);
      const cipherResp = await sendRawApdu('8021010000', `Phát hành ${studentId}: Đọc bản mã AES`);

      if (state.pendingIssueAvatarBytes && state.pendingIssueAvatarBytes.length > 0) {
        const bytes = state.pendingIssueAvatarBytes;
        for (let offset = 0; offset < bytes.length; offset += 240) {
          const chunk = bytes.slice(offset, Math.min(offset + 240, bytes.length));
          const p1 = ((offset >> 8) & 0xFF).toString(16).padStart(2, '0').toUpperCase();
          const p2 = (offset & 0xFF).toString(16).padStart(2, '0').toUpperCase();
          const lc = chunk.length.toString(16).padStart(2, '0').toUpperCase();
          await sendRawApdu(`8022${p1}${p2}${lc}${bytesToHex(chunk)}`, `Upload WebP @${offset}`);
        }
      }

      await apiJson('/api/cards', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          studentId,
          fullName,
          dob,
          className,
          department,
          defaultPin,
          cardLocked: false,
          trainingScore: 85,
          attendanceCount: 0,
          walletBalance: Number.isFinite(walletBalance) && walletBalance >= 0 ? walletBalance : 500000,
          publicKeyModulusHex: modResp.data || '',
          publicKeyExponentHex: expResp.data || '010001',
          rawAesCiphertextHex: cipherResp.data || ''
        })
      });

      closeModal('modalIssueCard');
      els.issueCardForm.reset();
      state.pendingIssueAvatarBytes = null;
      els.issueAvatarPreview.classList.add('hidden');
      els.issueAvatarPlaceholder.classList.remove('hidden');
      els.issueAvatarInfo.textContent = 'Tùy chọn. Hệ thống sẽ nén WebP < 8KB và chia gói APDU.';
      state.lastAvatarKey = '';

      showToast(`Đã phát hành thẻ ${studentId} (${fullName}) và lưu vào CSDL SQLite.`, 'success');
      await refreshAll();
    } catch (err) {
      els.issueFormError.textContent = err.message || 'Lỗi khi phát hành thẻ.';
      els.issueFormError.classList.remove('hidden');
    } finally {
      els.btnSubmitIssueCard.disabled = false;
      els.btnSubmitIssueCard.textContent = 'Ghi Thẻ & Lưu CSDL';
    }
  }

  function openPukModal(studentId, lock) {
    const cards = (state.summary && state.summary.cards) ? state.summary.cards : [];
    const card = cards.find((c) => c.studentId === studentId);
    if (!card) return;

    state.pukTarget = { card, lock };
    els.pukFormError.classList.add('hidden');
    els.pukCodeInput.value = '';
    els.pukDescription.innerHTML = lock
      ? `Khóa thẻ quản trị đối với sinh viên <strong>${esc(card.fullName)}</strong> (<span class="font-mono">${esc(card.studentId)}</span>). Sau khi khóa, mọi xác thực PIN đều bị từ chối (<span class="font-mono">SW=6983</span>).`
      : `Mở khóa thẻ cho sinh viên <strong>${esc(card.fullName)}</strong> (<span class="font-mono">${esc(card.studentId)}</span>). Lệnh thành công sẽ khôi phục 3 lần thử và đặt lại PIN mặc định về <span class="font-mono">123456</span>.`;
    els.btnSubmitPuk.textContent = lock ? 'Khóa Thẻ Ngay' : 'Mở Khóa Thẻ';
    openModal('modalPuk');
    setTimeout(() => els.pukCodeInput.focus(), 50);
  }

  async function handlePukSubmit(e) {
    e.preventDefault();
    if (!state.pukTarget) return;
    els.pukFormError.classList.add('hidden');

    const puk = els.pukCodeInput.value.trim();
    if (!/^\d{8}$/.test(puk)) {
      els.pukFormError.textContent = 'Mã PUK quản trị phải gồm đúng 8 chữ số.';
      els.pukFormError.classList.remove('hidden');
      return;
    }

    const { card, lock } = state.pukTarget;
    const pipeInfo = `${card.studentId}|${card.fullName}|${card.dob}|${card.className}|${card.department}`;
    els.btnSubmitPuk.disabled = true;

    try {
      await apiJson('/api/activate-card', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          studentId: card.studentId,
          studentInfo: pipeInfo,
          pin: card.defaultPin || '123456'
        })
      });
      await sendRawApdu(SELECT_APPLET_APDU, `PUK ${card.studentId}: Select Applet`);

      const p1 = lock ? '01' : '00';
      const pukHex = utf8ToHex(puk);
      const res = await sendRawApdu(`8012${p1}0008${pukHex}`, lock ? `Khóa thẻ ${card.studentId}` : `Mở khóa ${card.studentId}`);

      if (res.sw === '9000') {
        closeModal('modalPuk');
        showToast(
          lock ? `Đã khóa thẻ ${card.studentId} bằng PUK quản trị.` : `Đã mở khóa thẻ ${card.studentId} và khôi phục PIN về 123456.`,
          'success'
        );
        await refreshAll();
      } else if (res.sw === '6300') {
        els.pukFormError.textContent = 'Mã PUK quản trị không chính xác (SW=6300). Vui lòng kiểm tra lại.';
        els.pukFormError.classList.remove('hidden');
      } else {
        els.pukFormError.textContent = `Thao tác thất bại với mã trạng thái SW=${res.sw}.`;
        els.pukFormError.classList.remove('hidden');
      }
    } catch (err) {
      els.pukFormError.textContent = err.message || 'Lỗi kết nối BridgeServer.';
      els.pukFormError.classList.remove('hidden');
    } finally {
      els.btnSubmitPuk.disabled = false;
    }
  }

  async function openScrModal() {
    openModal('modalScr');
    els.scriptsListContainer.innerHTML = '<span class="form-hint">Đang tải danh sách file .scr...</span>';
    try {
      const scripts = await apiJson('/api/scripts');
      if (!scripts.length) {
        els.scriptsListContainer.innerHTML = '<span class="form-hint">Không tìm thấy file .scr nào trong javacard-applet/scripts/.</span>';
        return;
      }
      els.scriptsListContainer.innerHTML = scripts.map((s) => `
        <div class="script-item-row">
          <div>
            <div class="font-mono" style="font-size: 12px; font-weight: 500; color: var(--text-primary);">${esc(s.fileName)}</div>
            <div class="form-hint font-mono">${s.sizeBytes} bytes</div>
          </div>
          <button class="btn-action-outline btn-table" type="button" data-run-scr="${esc(s.fileName)}">Nạp kịch bản</button>
        </div>
      `).join('');
    } catch (err) {
      els.scriptsListContainer.innerHTML = `<span class="form-error">${esc(err.message)}</span>`;
    }
  }

  async function runScrPayload(payload, displayName) {
    try {
      const res = await apiJson('/api/load-scr', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (Array.isArray(res.logs)) {
        res.logs.forEach((line) => {
          const entry = document.createElement('div');
          entry.className = 'log-entry';
          entry.innerHTML = `<div class="log-line"><span class="log-time">[SCR]</span><span class="log-tx">${esc(line)}</span></div>`;
          els.apduLogScreen.appendChild(entry);
        });
        els.apduLogScreen.scrollTop = els.apduLogScreen.scrollHeight;
      }
      closeModal('modalScr');
      state.lastAvatarKey = '';
      showToast(`Đã nạp kịch bản ${displayName}. Thẻ hiện tại: ${res.activeStudentId}`, 'success');
      await refreshAll();
    } catch (err) {
      showToast(`Lỗi nạp script: ${err.message}`, 'error');
    }
  }

  function wireEvents() {
    els.toastCloseBtn.addEventListener('click', () => els.toastBanner.classList.add('hidden'));

    els.readerBtn.addEventListener('click', async () => {
      try {
        const st = await apiJson('/api/pcsc/reconnect', { method: 'POST' });
        state.status = st;
        renderHeaderAndHero();
        showToast(st.pcscConnected
          ? `Đã kết nối đầu đọc phần cứng: ${st.reader}`
          : 'Không tìm thấy đầu đọc vật lý; đang vận hành với Virtual JCIDE Engine.', 'info');
      } catch (err) {
        showToast(`Lỗi quét đầu đọc: ${err.message}`, 'error');
      }
    });

    els.btnTriggerPhone.addEventListener('click', async () => {
      const st = state.status;
      if (!st || !st.activeStudentId) return;
      const cards = (state.summary && state.summary.cards) ? state.summary.cards : [];
      const card = cards.find((c) => c.studentId === st.activeStudentId);
      const info = card
        ? `${card.studentId}|${card.fullName}|${card.dob}|${card.className}|${card.department}`
        : st.activeStudentInfo;
      const pin = card ? card.defaultPin : '123456';
      await activateCard(st.activeStudentId, info, pin, `Đã phát sự kiện chạm thẻ (tapEventId) cho ${st.activeStudentId}. Điện thoại sẽ tự hiện khung nhập PIN.`);
    });

    els.tabButtons.forEach((btn) => {
      btn.addEventListener('click', () => switchTab(btn.dataset.tab));
    });

    els.searchFilter.addEventListener('input', (e) => {
      state.searchQuery = e.target.value || '';
      renderActiveTable();
    });

    // Event delegation inside Cards table
    els.bodies.cards.addEventListener('click', async (e) => {
      const btn = e.target.closest('button[data-action]');
      if (!btn) return;
      const action = btn.dataset.action;
      const sid = btn.dataset.sid;
      const cards = (state.summary && state.summary.cards) ? state.summary.cards : [];
      const card = cards.find((c) => c.studentId === sid);
      if (!card) return;

      if (action === 'activate') {
        const info = `${card.studentId}|${card.fullName}|${card.dob}|${card.className}|${card.department}`;
        await activateCard(card.studentId, info, card.defaultPin || '123456', `Đã gắn thẻ ${card.studentId} (${card.fullName}) lên đầu đọc.`);
      } else if (action === 'puk-unlock') {
        openPukModal(sid, false);
      } else if (action === 'puk-lock') {
        openPukModal(sid, true);
      } else if (action === 'delete') {
        state.deleteTargetId = sid;
        els.deleteConfirmText.innerHTML = `Bạn có chắc chắn muốn xóa vĩnh viễn thẻ sinh viên <strong>${esc(card.fullName)}</strong> (<span class="font-mono">${esc(card.studentId)}</span>) và toàn bộ nhật ký liên quan khỏi SQLite?`;
        openModal('modalConfirmDelete');
      }
    });

    els.btnConfirmDelete.addEventListener('click', async () => {
      if (!state.deleteTargetId) return;
      const sid = state.deleteTargetId;
      try {
        await apiJson(`/api/cards?studentId=${encodeURIComponent(sid)}`, { method: 'DELETE' });
        closeModal('modalConfirmDelete');
        showToast(`Đã xóa thẻ ${sid} khỏi CSDL SQLite.`, 'success');
        await refreshAll();
      } catch (err) {
        showToast(`Không thể xóa thẻ: ${err.message}`, 'error');
      }
    });

    // APDU Presets & Submit
    document.querySelectorAll('.pill-btn[data-apdu]').forEach((pill) => {
      pill.addEventListener('click', () => {
        els.apduHexInput.value = pill.dataset.apdu;
        sendRawApdu(pill.dataset.apdu, pill.dataset.label)
          .then(() => refreshAll())
          .catch((err) => showToast(err.message, 'error'));
      });
    });

    els.apduForm.addEventListener('submit', async (e) => {
      e.preventDefault();
      try {
        await sendRawApdu(els.apduHexInput.value);
        await refreshAll();
      } catch (err) {
        showToast(err.message, 'error');
      }
    });

    els.btnClearApduLog.addEventListener('click', () => {
      els.apduLogScreen.innerHTML = '';
    });

    els.btnManualRefresh.addEventListener('click', () => refreshAll());

    // Issue Card Modal
    els.btnOpenIssueModal.addEventListener('click', () => {
      els.issueFormError.classList.add('hidden');
      openModal('modalIssueCard');
      setTimeout(() => document.getElementById('issueStudentId').focus(), 50);
    });

    els.issueAvatarFile.addEventListener('change', async (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) {
        state.pendingIssueAvatarBytes = null;
        els.issueAvatarPreview.classList.add('hidden');
        els.issueAvatarPlaceholder.classList.remove('hidden');
        return;
      }
      try {
        const webpBytes = await compressImageToWebpBytes(file);
        state.pendingIssueAvatarBytes = webpBytes;
        const previewUrl = URL.createObjectURL(new Blob([webpBytes], { type: 'image/webp' }));
        els.issueAvatarPreview.src = previewUrl;
        els.issueAvatarPreview.classList.remove('hidden');
        els.issueAvatarPlaceholder.classList.add('hidden');
        const packets = Math.ceil(webpBytes.length / 240);
        els.issueAvatarInfo.textContent = `Đã nén WebP 150×150: ${(webpBytes.length / 1024).toFixed(2)} KB (${packets} gói APDU).`;
      } catch (err) {
        els.issueAvatarInfo.textContent = err.message;
        state.pendingIssueAvatarBytes = null;
      }
    });

    els.issueCardForm.addEventListener('submit', handleIssueCardSubmit);
    els.pukForm.addEventListener('submit', handlePukSubmit);

    // SCR Modal
    els.btnOpenScrModal.addEventListener('click', openScrModal);
    els.scriptsListContainer.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-run-scr]');
      if (!btn) return;
      runScrPayload({ fileName: btn.dataset.runScr }, btn.dataset.runScr);
    });
    els.customScrFile.addEventListener('change', (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = (ev) => runScrPayload({ scriptContent: ev.target.result }, file.name);
      reader.readAsText(file, 'UTF-8');
    });

    // Export JSON
    els.btnExportJson.addEventListener('click', () => {
      if (!state.summary) return;
      const payload = {
        exportedAt: new Date().toISOString(),
        status: state.status,
        database: state.summary,
        walletTransactions: state.walletTx,
        auditLogs: state.auditLogs
      };
      const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `smartcard_snapshot_${Date.now()}.json`;
      a.click();
      URL.revokeObjectURL(url);
      showToast('Đã xuất bản sao lưu dữ liệu CSDL SQLite (.json).', 'success');
    });

    // Close modals via button, backdrop click, or Escape key
    document.querySelectorAll('[data-close-modal]').forEach((btn) => {
      btn.addEventListener('click', () => closeModal(btn.dataset.closeModal));
    });
    document.querySelectorAll('.modal-backdrop').forEach((backdrop) => {
      backdrop.addEventListener('click', (e) => {
        if (e.target === backdrop) backdrop.classList.add('hidden');
      });
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        document.querySelectorAll('.modal-backdrop').forEach((m) => m.classList.add('hidden'));
      }
    });
  }

  const portChip = document.getElementById('portChip');
  if (portChip && window.location.port) {
    portChip.textContent = `Port: ${window.location.port}`;
  }

  wireEvents();
  refreshAll();
  setInterval(refreshAll, 3000);
})();
