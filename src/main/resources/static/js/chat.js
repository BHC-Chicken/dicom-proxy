document.addEventListener('DOMContentLoaded', () => {
    // ==================== STATE MANAGEMENT ====================
    let currentSessionId = null;
    let availableAuthors = [];
    let availableSpaceKeys = [];
    let activeFilters = {
        topK: 8,
        author: '',
        spaceKey: '',
        startDate: '',
        endDate: ''
    };

    // ==================== DOM ELEMENTS ====================
    const sessionListEl = document.getElementById('session-list');
    const messagesContainerEl = document.getElementById('messages-container');
    const chatInputEl = document.getElementById('chat-input');
    const btnSendEl = document.getElementById('btn-send');
    const btnNewChatEl = document.getElementById('btn-new-chat');
    const currentSessionTitleEl = document.getElementById('current-session-title');

    // Filter Modal Elements
    const btnOpenFilterEl = document.getElementById('btn-open-filter');
    const btnCloseModalEl = document.getElementById('btn-close-modal');
    const filterModalEl = document.getElementById('filter-modal');
    const filterBadgeEl = document.getElementById('filter-badge');

    const filterAuthorSearchEl = document.getElementById('filter-author-search');
    const filterAuthorDropdownEl = document.getElementById('filter-author-dropdown');
    const filterSpaceSearchEl = document.getElementById('filter-space-search');
    const filterSpaceDropdownEl = document.getElementById('filter-space-dropdown');
    const filterStartDateEl = document.getElementById('filter-start-date');
    const filterEndDateEl = document.getElementById('filter-end-date');
    const filterTopKEl = document.getElementById('filter-topk');

    const btnApplyFilterEl = document.getElementById('btn-apply-filter-modal');
    const btnResetFilterEl = document.getElementById('btn-reset-filter-modal');
    const activeFilterBarEl = document.getElementById('active-filter-bar');
    const filterTagsEl = document.getElementById('filter-tags');
    const btnClearFiltersEl = document.getElementById('btn-clear-filters');

    // ==================== INITIALIZATION ====================
    init();

    async function init() {
        marked.setOptions({
            highlight: function (code, lang) {
                if (lang && hljs.getLanguage(lang)) {
                    return hljs.highlight(code, { language: lang }).value;
                }
                return hljs.highlightAuto(code).value;
            },
            breaks: true
        });

        await loadMetadataOptions();
        await loadSessions();

        // 쿼리 입력창 유효성 검사 및 자동 높이 조절
        chatInputEl.addEventListener('input', handleInputChange);
        chatInputEl.addEventListener('keydown', handleInputKeyDown);
        btnSendEl.addEventListener('click', handleSendMessage);
        btnNewChatEl.addEventListener('click', handleCreateNewChat);

        // 필터 모달 이벤트
        btnOpenFilterEl.addEventListener('click', () => filterModalEl.classList.remove('hidden'));
        btnCloseModalEl.addEventListener('click', () => filterModalEl.classList.add('hidden'));
        btnResetFilterEl.addEventListener('click', resetFilterModalInputs);
        btnApplyFilterEl.addEventListener('click', applyFiltersFromModal);
        btnClearFiltersEl.addEventListener('click', clearActiveFilters);

        // 드롭다운 검색 이벤트
        setupDropdownSearch(filterAuthorSearchEl, filterAuthorDropdownEl, () => availableAuthors);
        setupDropdownSearch(filterSpaceSearchEl, filterSpaceDropdownEl, () => availableSpaceKeys);
    }

    // ==================== METADATA & SESSION APIS ====================
    async function loadMetadataOptions() {
        try {
            const res = await fetch('/api/v1/knowledge/metadata-options');
            if (res.ok) {
                const data = await res.json();
                availableAuthors = data.authors || [];
                availableSpaceKeys = data.spaceKeys || [];
            }
        } catch (e) {
            console.error('메타데이터 옵션 수신 실패:', e);
        }
    }

    async function loadSessions() {
        try {
            const res = await fetch('/api/v1/chat/sessions');
            if (res.ok) {
                const sessions = await res.json();
                renderSessionList(sessions);

                if (sessions.length > 0) {
                    if (!currentSessionId) {
                        selectSession(sessions[0].id, sessions[0].title);
                    }
                } else {
                    await handleCreateNewChat();
                }
            }
        } catch (e) {
            console.error('세션 목록 조회 실패:', e);
        }
    }

    function renderSessionList(sessions) {
        sessionListEl.innerHTML = '';
        sessions.forEach(s => {
            const div = document.createElement('div');
            div.className = `session-item ${s.id === currentSessionId ? 'active' : ''}`;
            div.innerHTML = `
                <i class="fa-regular fa-message"></i>
                <span class="session-title">${escapeHtml(s.title)}</span>
                <button class="btn-delete-session" title="세션 삭제"><i class="fa-solid fa-trash-can"></i></button>
            `;

            div.querySelector('.session-title').addEventListener('click', () => selectSession(s.id, s.title));
            div.querySelector('.btn-delete-session').addEventListener('click', (e) => {
                e.stopPropagation();
                deleteSession(s.id);
            });

            sessionListEl.appendChild(div);
        });
    }

    async function selectSession(sessionId, title) {
        currentSessionId = sessionId;
        currentSessionTitleEl.textContent = title || '새로운 대화';
        loadSessions(); // active 클래스 갱신

        try {
            const res = await fetch(`/api/v1/chat/sessions/${sessionId}/messages`);
            if (res.ok) {
                const messages = await res.json();
                renderMessages(messages);
            }
        } catch (e) {
            console.error('대화 내역 조회 실패:', e);
        }
    }

    async function handleCreateNewChat() {
        try {
            const res = await fetch('/api/v1/chat/sessions', { method: 'POST' });
            if (res.ok) {
                const newSession = await res.json();
                await loadSessions();
                selectSession(newSession.id, newSession.title);
            }
        } catch (e) {
            console.error('신규 세션 생성 실패:', e);
        }
    }

    async function deleteSession(sessionId) {
        if (!confirm('정말 이 대화를 삭제하시겠습니까?')) return;
        try {
            const res = await fetch(`/api/v1/chat/sessions/${sessionId}`, { method: 'DELETE' });
            if (res.ok) {
                if (currentSessionId === sessionId) {
                    currentSessionId = null;
                }
                await loadSessions();
            }
        } catch (e) {
            console.error('세션 삭제 실패:', e);
        }
    }

    // ==================== MESSAGING ====================
    function renderMessages(messages) {
        messagesContainerEl.innerHTML = '';
        if (messages.length === 0) {
            renderWelcomeMessage();
            return;
        }

        messages.forEach(msg => {
            appendMessageBubble(msg.role, msg.content, msg.sources, msg.filtersJson);
        });
        scrollToBottom();
    }

    function renderWelcomeMessage() {
        messagesContainerEl.innerHTML = `
            <div class="msg-row assistant">
                <div class="msg-avatar"><i class="fa-solid fa-robot"></i></div>
                <div class="msg-bubble">
                    안녕하세요! 사내 Confluence 지식 데이터베이스 기반 AI 조력자입니다.<br>
                    궁금하신 사항을 질문해 주세요. 하단 질문 입력창 좌측의 필터 버튼(<i class="fa-solid fa-sliders"></i>)을 통해 작성자, 공간, 날짜 범위 필터링 및 <strong>참조 문서 검색 개수(topK, 기본 8개)</strong>를 직접 지정할 수 있습니다.
                </div>
            </div>
        `;
    }

    function handleInputChange() {
        const val = chatInputEl.value.trim();
        btnSendEl.disabled = val.length === 0; // 공백만 있을 경우 제출 불가

        // 텍스트 영역 높이 자동 조절
        chatInputEl.style.height = 'auto';
        chatInputEl.style.height = Math.min(chatInputEl.scrollHeight, 150) + 'px';
    }

    function handleInputKeyDown(e) {
        if (e.isComposing || e.keyCode === 229) {
            return;
        }
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            if (!btnSendEl.disabled) {
                handleSendMessage();
            }
        }
    }

    async function handleSendMessage() {
        const query = chatInputEl.value.trim();
        if (!query) return;

        // 현재 활성화된 필터 캡처
        const currentFilters = { ...activeFilters };

        // 사용자 입력창 초기화
        chatInputEl.value = '';
        chatInputEl.style.height = 'auto';
        btnSendEl.disabled = true;

        // 1. USER 메시지 UI 표시
        appendMessageBubble('USER', query, null, JSON.stringify(currentFilters));
        scrollToBottom();

        // 2. 로딩 애니메이션 표시
        const loadingEl = appendLoadingIndicator();
        scrollToBottom();

        try {
            const payload = {
                sessionId: currentSessionId,
                query: query,
                topK: currentFilters.topK,
                author: currentFilters.author,
                spaceKey: currentFilters.spaceKey,
                startDate: currentFilters.startDate,
                endDate: currentFilters.endDate
            };

            const res = await fetch('/api/v1/chat/send', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            loadingEl.remove();

            if (res.ok) {
                const assistantMsg = await res.json();
                appendMessageBubble('ASSISTANT', assistantMsg.content, assistantMsg.sources, assistantMsg.filtersJson);
                await loadSessions(); // 세션 목록 및 제목 갱신
            } else {
                appendMessageBubble('ASSISTANT', '답변을 생성하는 중에 오류가 발생했습니다.');
            }

        } catch (e) {
            loadingEl.remove();
            console.error('메시지 전송 실패:', e);
            appendMessageBubble('ASSISTANT', '통신 중 오류가 발생했습니다.');
        }

        scrollToBottom();
    }

    function appendMessageBubble(role, content, sources, filtersJson) {
        const isUser = role === 'USER';
        const row = document.createElement('div');
        row.className = `msg-row ${isUser ? 'user' : 'assistant'}`;

        // LaTeX 화살표 기호($\rightarrow$, \rightarrow)를 유니코드 화살표(→)로 자동 보정
        let cleanContent = content
            ? content.replace(/\\\$\s*\\rightarrow\s*\\\$/g, '→')
                .replace(/\$\s*\\rightarrow\s*\$/g, '→')
                .replace(/\\rightarrow/g, '→')
            : '';

        let avatarHtml = isUser ? '' : `<div class="msg-avatar"><i class="fa-solid fa-robot"></i></div>`;
        let parsedContent = isUser ? escapeHtml(cleanContent) : marked.parse(cleanContent);

        // 적용된 필터 태그 렌더링 로직
        let filterTagsHtml = '';
        if (filtersJson) {
            try {
                const f = typeof filtersJson === 'string' ? JSON.parse(filtersJson) : filtersJson;
                const tags = [];
                if (f.topK && f.topK !== 8) tags.push(`TopK: ${f.topK}개`);
                if (f.author) tags.push(`작성자: ${escapeHtml(f.author)}`);
                if (f.spaceKey) tags.push(`공간: ${escapeHtml(f.spaceKey)}`);
                if (f.startDate || f.endDate) {
                    tags.push(`기간: ${escapeHtml(f.startDate || '전체')} ~ ${escapeHtml(f.endDate || '전체')}`);
                }
                if (tags.length > 0) {
                    const tagItems = tags.map(t => `<span class="msg-filter-tag">${t}</span>`).join('');
                    filterTagsHtml = `<div class="msg-filter-tags"><i class="fa-solid fa-filter"></i> ${tagItems}</div>`;
                }
            } catch (e) {
                console.warn('filtersJson 파싱 오류:', e);
            }
        }

        let sourcesHtml = '';
        if (!isUser && sources && sources.length > 0) {
            const sourcesListHtml = sources.map((s, idx) => `
                <div class="source-card">
                    <div class="source-header">
                        <span>[${idx + 1}] ${escapeHtml(s.title)} (${escapeHtml(s.section)})</span>
                        ${s.sourceUrl ? `<a href="${escapeHtml(s.sourceUrl)}" target="_blank" style="color:#38bdf8;"><i class="fa-solid fa-arrow-up-right-from-square"></i></a>` : ''}
                    </div>
                    <div class="source-path">${escapeHtml(s.headerPath)}</div>
                </div>
            `).join('');

            sourcesHtml = `
                <div class="sources-toggle">
                    <button class="btn-toggle-sources"><i class="fa-solid fa-book-bookmark"></i> 참고 문서 (${sources.length}개) <i class="fa-solid fa-chevron-down"></i></button>
                    <div class="sources-list hidden">${sourcesListHtml}</div>
                </div>
            `;
        }

        row.innerHTML = `
            ${avatarHtml}
            <div class="msg-bubble">
                ${filterTagsHtml}
                ${parsedContent}
                ${sourcesHtml}
            </div>
        `;

        if (!isUser && sources && sources.length > 0) {
            const btnToggle = row.querySelector('.btn-toggle-sources');
            const sourcesList = row.querySelector('.sources-list');
            btnToggle.addEventListener('click', () => {
                sourcesList.classList.toggle('hidden');
            });
        }

        messagesContainerEl.appendChild(row);
    }

    function appendLoadingIndicator() {
        const row = document.createElement('div');
        row.className = 'msg-row assistant';
        row.innerHTML = `
            <div class="msg-avatar"><i class="fa-solid fa-robot"></i></div>
            <div class="msg-bubble">
                <i class="fa-solid fa-circle-notch fa-spin"></i> Confluence 사내 지식을 검색하고 답변을 생성하고 있습니다...
            </div>
        `;
        messagesContainerEl.appendChild(row);
        return row;
    }

    function scrollToBottom() {
        messagesContainerEl.scrollTop = messagesContainerEl.scrollHeight;
    }

    // ==================== FILTER MODAL & DROPDOWN SEARCH ====================
    function setupDropdownSearch(inputEl, dropdownEl, optionsGetter) {
        inputEl.addEventListener('focus', () => renderDropdownOptions(inputEl, dropdownEl, optionsGetter()));
        inputEl.addEventListener('input', () => renderDropdownOptions(inputEl, dropdownEl, optionsGetter()));

        document.addEventListener('click', (e) => {
            if (!inputEl.contains(e.target) && !dropdownEl.contains(e.target)) {
                dropdownEl.classList.add('hidden');
            }
        });
    }

    function renderDropdownOptions(inputEl, dropdownEl, options) {
        const keyword = inputEl.value.trim().toLowerCase();
        const filtered = options.filter(opt => opt.toLowerCase().includes(keyword));

        dropdownEl.innerHTML = '';
        if (filtered.length === 0) {
            dropdownEl.classList.add('hidden');
            return;
        }

        filtered.forEach(opt => {
            const li = document.createElement('li');
            li.className = 'dropdown-item';
            li.textContent = opt;
            li.addEventListener('click', () => {
                inputEl.value = opt;
                dropdownEl.classList.add('hidden');
            });
            dropdownEl.appendChild(li);
        });

        dropdownEl.classList.remove('hidden');
    }

    function applyFiltersFromModal() {
        activeFilters.author = filterAuthorSearchEl.value.trim();
        activeFilters.spaceKey = filterSpaceSearchEl.value.trim();
        activeFilters.startDate = filterStartDateEl.value;
        activeFilters.endDate = filterEndDateEl.value;

        const parsedTopK = parseInt(filterTopKEl.value, 10);
        activeFilters.topK = isNaN(parsedTopK) || parsedTopK <= 0 ? 8 : parsedTopK;

        filterModalEl.classList.add('hidden');
        renderActiveFilterBar();
    }

    function resetFilterModalInputs() {
        filterAuthorSearchEl.value = '';
        filterSpaceSearchEl.value = '';
        filterStartDateEl.value = '';
        filterEndDateEl.value = '';
        filterTopKEl.value = '8';
    }

    function clearActiveFilters() {
        resetFilterModalInputs();
        applyFiltersFromModal();
    }

    function renderActiveFilterBar() {
        const tags = [];
        if (activeFilters.topK && activeFilters.topK !== 8) tags.push(`TopK: ${activeFilters.topK}개`);
        if (activeFilters.author) tags.push(`작성자: ${activeFilters.author}`);
        if (activeFilters.spaceKey) tags.push(`공간: ${activeFilters.spaceKey}`);
        if (activeFilters.startDate || activeFilters.endDate) {
            tags.push(`기간: ${activeFilters.startDate || '전체'} ~ ${activeFilters.endDate || '전체'}`);
        }

        filterTagsEl.innerHTML = '';
        if (tags.length > 0) {
            tags.forEach(t => {
                const span = document.createElement('span');
                span.className = 'tag-item';
                span.textContent = t;
                filterTagsEl.appendChild(span);
            });
            activeFilterBarEl.classList.remove('hidden');
            filterBadgeEl.classList.remove('hidden');
        } else {
            activeFilterBarEl.classList.add('hidden');
            filterBadgeEl.classList.add('hidden');
        }
    }

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
    }
});
