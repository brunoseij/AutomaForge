/* AutomaForge Android — interface hospedada no celular controlador. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const params = new URLSearchParams(location.search);
  const roles = ['controller', 'board', 'cards'];
  let code = (params.get('room') || '').trim().toUpperCase();
  let role = roles.includes(params.get('role')) ? params.get('role') : 'controller';
  let room = null;
  let health = null;
  let ws = null;
  let leaving = false;
  let announcedId = null;
  let observedFirstAnnouncement = false;
  let boardVersion = -1;
  let timer = null;
  let busyPhoto = false;
  let processingTimer = null;
  let processingPollBusy = false;
  let toastTimer = null;
  const cameras = { board: null, cards: null };
  $('voiceEnabled').checked = localStorage.getItem('automaforge_voice') === 'yes';

  function toast(message, isError = false) {
    clearTimeout(toastTimer);
    const el = $('toast'); el.textContent = message; el.className = 'toast' + (isError ? ' error' : '');
    toastTimer = setTimeout(() => el.classList.add('hidden'), 4900);
  }
  function status(text, kind = '') {
    $('statusText').textContent = text;
    $('statusDot').className = 'status-dot ' + kind;
  }
  async function request(path, options = {}) {
    const opts = {...options};
    if (opts.json !== undefined) {
      opts.body = JSON.stringify(opts.json);
      opts.headers = {...opts.headers, 'Content-Type': 'application/json'};
      delete opts.json;
    }
    let response;
    try { response = await fetch(path, opts); }
    catch (e) { throw new Error('Servidor indisponível. Confira Wi-Fi e endereço: ' + e.message); }
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      let message = typeof data.detail === 'string' ? data.detail : JSON.stringify(data.detail || data);
      throw new Error(message || `Erro HTTP ${response.status}`);
    }
    return data;
  }
  function run(fn) { return async event => { if (event?.preventDefault) event.preventDefault();
    const button = event?.currentTarget;
    try {
      if (button?.tagName === 'BUTTON') button.disabled = true;
      await fn(event);
    } catch (error) { console.error(error); toast(error.message || 'Ocorreu um erro.', true); }
    finally { if (button?.tagName === 'BUTTON') button.disabled = false; }
  }; }
  const api = suffix => `/api/rooms/${encodeURIComponent(code)}${suffix}`;
  const linkFor = r => `${window.AutomaAndroid ? window.AutomaAndroid.lanAddress() : location.origin}/?room=${code}&role=${r}`;
  async function copyText(text) {
    try { await navigator.clipboard.writeText(text); toast('Link copiado.'); }
    catch (_) { window.prompt('Copie o link:', text); }
  }
  function updateURL() { history.replaceState(null, '', code ? `/?room=${code}&role=${role}` : '/'); }

  function switchRole(next) {
    role = next; updateURL();
    for (const r of roles) {
      $('role' + (r === 'controller' ? 'Controller' : r === 'board' ? 'Board' : 'Cards')).classList.toggle('selected', r === role);
      $(r === 'controller' ? 'controllerPane' : r === 'board' ? 'boardPane' : 'cardsPane').classList.toggle('hidden', r !== role);
    }
    const helps = {
      controller: 'Este aparelho configura a partida, aprova movimentos e pode falar pela automa.',
      board: 'Deixe este celular fixo no tripé, apontado para o tabuleiro e com a tela ligada.',
      cards: 'Use este aparelho ou uma câmera na caixa de leitura privada, sem mostrar as cartas ao jogador.'
    };
    $('roleHelp').textContent = helps[role];
    if (role !== 'board') { $('boardAuto').checked = false; stopCamera('board'); }
    if (role !== 'cards') stopCamera('cards');
  }
  function connectWebsocket() {
    if (ws) { ws.onclose = null; ws.close(); }
    if (!code || leaving) return;
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${scheme}//${location.host}/ws/${code}`);
    ws.onopen = () => status('Sincronizado na rede local', 'online');
    ws.onmessage = event => {
      const msg = JSON.parse(event.data);
      if (msg.type === 'state') receiveState(msg.room);
      if (msg.type === 'board') refreshBoard(msg.board_version);
    };
    ws.onerror = () => status('Conexão instável', 'error');
    ws.onclose = () => {
      status('Reconectando…');
      if (!leaving && code) setTimeout(connectWebsocket, 3000);
    };
  }
  function enterRoom(data) {
    code = data.code;
    room = data;
    leaving = false;
    $('landing').classList.add('hidden');
    $('workspace').classList.remove('hidden');
    updateURL(); switchRole(role); receiveState(data);
    connectWebsocket();
    clearInterval(processingTimer);
    processingTimer = setInterval(() => refreshProcessing().catch(() => {}), 1800);
    refreshProcessing().catch(() => {});
  }
  function formIsBeingEdited(formId) {
    const active = document.activeElement;
    return active && $(formId).contains(active) && ['INPUT', 'TEXTAREA', 'SELECT'].includes(active.tagName);
  }
  function parseActions(text) {
    return text.split(/\r?\n/).map(line => {
      const parts = line.split('|').map(x => x.trim());
      return {name: parts[0], description: parts[1] || '', precondition: parts.slice(2).join('|') || ''};
    }).filter(x => x.name);
  }
  function actionLines(actions) {
    return (actions || []).map(a => [a.name, a.description || '', a.precondition || ''].join(' | ')).join('\n');
  }
  function fillRules(src) {
    $('rulesReviewed').checked = false;
    $('editGame').value = src.game_name || room?.game_name || '';
    $('editRules').value = src.rules_text || '';
    $('editSetup').value = src.setup || '';
    $('editTurnFlow').value = src.turn_flow || '';
    $('editActions').value = actionLines(src.actions);
    $('editUncertainties').value = (src.uncertainties || []).join('\n');
  }
  function fillState(src) {
    $('editTurn').value = src.turn;
    $('editPhase').value = src.phase;
    $('editVariables').value = Object.entries(src.variables || {}).map(([k, v]) => k + ' = ' + v).join('\n');
    $('editNotes').value = src.board_notes || '';
  }
  function refreshBoard(version) {
    if (!Number.isInteger(version) || version < 1 || version === boardVersion) return;
    boardVersion = version;
    $('boardImage').src = api('/board/photo') + '?v=' + version;
    $('boardImage').classList.remove('hidden');
    $('boardPlaceholder').classList.add('hidden');
    $('boardAge').textContent = `Foto ${version}`;
  }
  function speak(message) {
    if (!$('voiceEnabled').checked || !message) return;
    if (window.AutomaAndroid) { window.AutomaAndroid.speak(message); return; }
    if (!('speechSynthesis' in window)) { toast('Este navegador não oferece síntese de voz.', true); return; }
    speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(message);
    utterance.lang = 'pt-BR'; utterance.rate = .96;
    const voices = speechSynthesis.getVoices();
    const ptVoice = voices.find(v => v.lang.toLowerCase().startsWith('pt-br')) || voices.find(v => v.lang.toLowerCase().startsWith('pt'));
    if (ptVoice) utterance.voice = ptVoice;
    speechSynthesis.speak(utterance);
  }
  function receiveState(data) {
    const oldActions = JSON.stringify(room?.actions || []);
    room = data;
    $('roomTitle').textContent = data.game_name;
    $('roomCode').textContent = data.code;
    $('roomTurn').textContent = data.turn;
    $('rulesChip').textContent = data.actions.length + ' ações';
    $('rulesCount').textContent = data.rule_pages.length + ' arquivos do manual carregados';
    $('privateCount').textContent = data.private_card_count;
    $('turnChip').textContent = data.proposal ? 'Revisão pendente' : 'Aguardando jogada';
    if (!formIsBeingEdited('rulesForm')) fillRules(data.rules_draft || data);
    if (!formIsBeingEdited('stateForm')) fillState(data);
    if (oldActions !== JSON.stringify(data.actions) || !$('manualAction').options.length) fillManualActions();
    renderDraft(); renderProposal(); renderHistory(); renderObservation();
    refreshBoard(data.board_version);
    const announcement = data.last_announcement;
    if (!observedFirstAnnouncement) {
      observedFirstAnnouncement = true;
      announcedId = announcement?.id || null;
    } else if (role === 'controller' && announcement && announcement.id !== announcedId) {
      announcedId = announcement.id;
      speak(announcement.text);
    }
  }
  function fillManualActions() {
    const sel = $('manualAction');
    const value = sel.value;
    sel.innerHTML = (room?.actions || []).map(a => `<option value="${esc(a.name)}">${esc(a.name)}</option>`).join('') || '<option value="">Nenhuma ação cadastrada</option>';
    if ([...sel.options].some(o => o.value === value)) sel.value = value;
  }
  function renderDraft() {
    const target = $('draftBox');
    if (!room.rules_draft) { target.classList.add('hidden'); return; }
    target.classList.remove('hidden');
    target.innerHTML = `<strong>Rascunho da IA, ainda não confirmado.</strong><p>Revise os campos abaixo, corrija ambiguidades e clique em “Salvar regras revisadas”.</p>` +
      `<strong>Pontos a verificar:</strong><ul>${(room.rules_draft.uncertainties || []).map(u => `<li>${esc(u)}</li>`).join('') || '<li>Sem dúvidas indicadas; ainda é necessária a revisão humana.</li>'}</ul>`;
  }
  function renderProposal() {
    const el = $('proposalArea');
    const p = room.proposal;
    if (!p) { el.className = 'proposal-empty'; el.textContent = 'Nenhuma jogada pendente.'; return; }
    el.className = 'proposal';
    el.innerHTML = `<div class="eyebrow">AGUARDANDO SUA APROVAÇÃO</div><h3>${esc(p.action)}</h3>` +
      `<p><strong>Fala:</strong> “${esc(p.spoken)}”</p><p><strong>Justificativa:</strong> ${esc(p.reason)}</p>` +
      (p.player_steps.length ? `<strong>Como executar:</strong><ol>${p.player_steps.map(s => `<li>${esc(s)}</li>`).join('')}</ol>` : '') +
      (p.state_changes.length ? `<p><strong>Alterações propostas:</strong> ${esc(p.state_changes.map(x => x.key + ' = ' + x.value).join('; '))}</p>` : '') +
      (p.uncertainties.length ? `<p class="risk"><strong>Verifique:</strong> ${esc(p.uncertainties.join('; '))}</p>` : '') +
      `<p class="hint">Confiança declarada: ${esc(p.confidence)}. Não significa validação formal das regras.</p>` +
      `<div class="button-row"><button class="primary" id="confirmProposal">Confirmar e anunciar</button><button class="secondary" id="rejectProposal">Rejeitar</button></div>`;
    $('confirmProposal').addEventListener('click', run(async () => { await request(api('/automa/confirm'), {method:'POST'}); toast('Jogada aprovada.'); }));
    $('rejectProposal').addEventListener('click', run(async () => { await request(api('/automa/reject'), {method:'POST'}); toast('Jogada descartada.'); }));
  }
  function renderHistory() {
    const el = $('historyArea');
    if (!room.history.length) { el.innerHTML = 'Sem jogadas aprovadas.'; return; }
    el.innerHTML = room.history.slice(-12).reverse().map(h =>
      `<div class="history-entry"><span class="history-turn"># ${esc(h.turn)}</span><div><strong>${esc(h.action)}</strong><p>${esc(h.spoken)}</p></div></div>`).join('');
  }
  function renderObservation() {
    const o = room.board_observation;
    $('observationArea').innerHTML = !o ? 'A análise visual é opcional e deve ser conferida.' :
      `<strong>Observações:</strong><ul>${o.observations.map(x => `<li>${esc(x)}</li>`).join('')}</ul>` +
      `<strong>Possíveis alterações:</strong><ul>${o.possible_changes.map(x => `<li>${esc(x)}</li>`).join('')}</ul>` +
      `<strong>Dúvidas:</strong><ul>${o.uncertainties.map(x => `<li>${esc(x)}</li>`).join('') || '<li>Nenhuma sinalizada.</li>'}</ul>`;
  }
  async function photoBlob(videoId) {
    const video = $(videoId);
    if (!video.videoWidth || !video.videoHeight) throw new Error('Ative a câmera antes de enviar uma fotografia.');
    const canvas = document.createElement('canvas');
    const scale = Math.min(1, 1600 / Math.max(video.videoWidth, video.videoHeight));
    canvas.width = Math.round(video.videoWidth * scale);
    canvas.height = Math.round(video.videoHeight * scale);
    canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
    return await new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Falha na captura.')), 'image/jpeg', .84));
  }
  async function uploadFile(path, blob, filename = 'foto.jpg') {
    const form = new FormData(); form.append('file', blob, filename);
    return await request(api(path), {method: 'POST', body: form});
  }
  async function startCamera(which) {
    if (window.AutomaAndroid) {
      window.AutomaAndroid.launchCamera(which, code);
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('Em HTTP, o navegador não permite vídeo ao vivo. Use o APK no celular da câmera ou o campo "Fotografar/selecionar" abaixo.');
    stopCamera(which);
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false, video: {facingMode: {ideal:'environment'}, width: {ideal: 1920}, height: {ideal: 1080}}
    });
    cameras[which] = stream;
    const video = $(which === 'board' ? 'boardVideo' : 'cardsVideo');
    video.srcObject = stream; await video.play(); video.classList.add('active');
    $(which === 'board' ? 'boardFallback' : 'cardsFallback').classList.add('hidden');
  }
  function stopCamera(which) {
    const stream = cameras[which];
    if (stream) stream.getTracks().forEach(track => track.stop());
    cameras[which] = null;
    const video = $(which === 'board' ? 'boardVideo' : 'cardsVideo');
    video.srcObject = null; video.classList.remove('active');
    $(which === 'board' ? 'boardFallback' : 'cardsFallback').classList.remove('hidden');
  }
  async function uploadBoardPhoto(silent = false) {
    if (busyPhoto) return;
    busyPhoto = true;
    try {
      const result = await uploadFile('/board/photo', await photoBlob('boardVideo'));
      $('boardFeedback').textContent = `Fotografia ${result.board_version} recebida pelo servidor.`;
      if (!silent) toast('Imagem do tabuleiro atualizada.');
    } finally { busyPhoto = false; }
  }
  function autoBoardInterval() {
    clearInterval(timer); timer = null;
    if (window.AutomaAndroid) return; // a captura automática é responsabilidade da CameraActivity
    if (!$('boardAuto').checked) return;
    timer = setInterval(() => {
      if (role === 'board' && cameras.board && !document.hidden) {
        uploadBoardPhoto(true).catch(error => {
          $('boardFeedback').textContent = error.message;
        });
      }
    }, 5000);
  }

  $('createForm').addEventListener('submit', run(async () => {
    const data = await request('/api/rooms', {method:'POST', json:{game_name:$('newName').value}});
    enterRoom(data); toast('Sala criada. Abra o mesmo endereço nos outros aparelhos.');
  }));
  $('joinForm').addEventListener('submit', run(async () => {
    const c = $('joinCode').value.replace(/\s/g, '').toUpperCase();
    const data = await request(`/api/rooms/${encodeURIComponent(c)}`);
    enterRoom(data);
  }));
  for (const [id, r] of [['roleController','controller'],['roleBoard','board'],['roleCards','cards']]) {
    $(id).addEventListener('click', () => switchRole(r));
  }
  $('leaveRoom').addEventListener('click', () => { leaving = true; clearInterval(timer); clearInterval(processingTimer); stopCamera('board'); stopCamera('cards');
    if (ws) ws.close(); code = ''; room = null; updateURL(); location.reload(); });
  $('copyLink').addEventListener('click', () => copyText(linkFor(role)));

  $('uploadRules').addEventListener('click', run(async () => {
    const files = [...$('rulePhotos').files];
    if (!files.length) throw new Error('Selecione o PDF do manual ou fotografias das páginas.');
    const form = new FormData(); files.forEach(file => form.append('files', file));
    const data = await request(api('/rules/pages'), {method:'POST', body:form});
    $('rulePhotos').value = '';
    toast(`${data.pages_count} arquivos do manual carregados nesta sala.`);
  }));
  async function refreshProcessing() {
    if (!code || role !== 'controller' || processingPollBusy) return;
    processingPollBusy = true;
    try {
      const state = await request(api('/rules/processing'));
      $('processingStage').textContent = `${state.stage} · ${state.state}`;
      $('processingProgress').max = state.total || 1;
      $('processingProgress').value = state.page || 0;
      $('processingDetails').textContent = `${state.page || 0}/${state.total || 0} páginas · OCR: ${state.ocr} · Visual: ${state.visual}. ${state.message || ''}`;
      const m = state.metrics || {};
      const bytes = n => `${((n || 0)/1024).toFixed(1)} KiB`;
      $('processingMetrics').textContent = `Páginas: ${m.pages || 0} (nativas ${m.nativePages || 0}, OCR ${m.ocrPages || 0}). ` +
        `Elementos visuais: ${m.visualsIdentified || 0}; imagens confirmadas/tentadas: ${m.imagesTransmitted || 0}/${m.imagesAttempted || 0}. ` +
        `Originais: ${bytes(m.originalPdfBytes)}; JSON escrito na conexão: ${bytes(m.sentRequestBytes)} (preparado: ${bytes(m.attemptedRequestBytes)}); diferença: ${bytes(m.byteDifference)}. ` +
        `Chamadas nesta execução: ${m.calls || 0}; cache hit/miss: ${m.cacheHits || 0}/${m.cacheMisses || 0}. ` +
        `Tempo local/remoto: ${m.localMs || 0}/${m.remoteMs || 0} ms. ` +
        (m.usageAvailable ? `Tokens informados pela API: entrada ${m.inputTokens}, saída ${m.outputTokens}. ` : 'Tokens faturados indisponíveis. ') +
        'Economia de tokens e de custo: sem medição de referência; não calculada.';
      $('interpretRules').disabled = state.state === 'running';
      $('cancelRules').disabled = state.state !== 'running';
      if (state.state === 'needs_review' && state.draft && !room.rules_draft && !room.rules_text) {
        receiveState(await request(api('')));
      }
    } finally { processingPollBusy = false; }
  }
  $('interpretRules').addEventListener('click', run(async () => {
    await request(api('/rules/interpret'), {method:'POST', json:{retry_uncertain:$('retryUncertain').checked, original_fallback:$('originalFallback').checked}});
    $('retryUncertain').checked = false;
    await refreshProcessing();
    toast('Processamento iniciado. É possível cancelar e retomar pelos checkpoints.');
  }));
  $('cancelRules').addEventListener('click', run(async () => {
    await request(api('/rules/cancel'), {method:'POST'});
    toast('Cancelamento solicitado. Aguarde a liberação dos recursos.');
  }));
  $('deleteManuals').addEventListener('click', run(async () => {
    if (!confirm('Apagar os arquivos importados e o cache destes manuais? As regras salvas na sala permanecem.')) return;
    await request(api('/rules/manuals'), {method:'DELETE'});
    await refreshProcessing();
    toast('Manuais e cache apagados.');
  }));
  $('rulesForm').addEventListener('submit', run(async () => {
    const body = {human_validated:$('rulesReviewed').checked, game_name:$('editGame').value, rules_text:$('editRules').value,
      setup:$('editSetup').value, turn_flow:$('editTurnFlow').value,
      actions:parseActions($('editActions').value),
      uncertainties:$('editUncertainties').value.split(/\r?\n/).map(s=>s.trim()).filter(Boolean)};
    const data = await request(api('/rules'), {method:'PUT', json:body});
    receiveState(data); toast('Regras salvas e disponíveis para a automa.');
  }));
  $('stateForm').addEventListener('submit', run(async () => {
    const variables = {};
    for (const line of $('editVariables').value.split(/\r?\n/)) {
      if (!line.trim()) continue;
      const pos = line.indexOf('=');
      if (pos < 1) throw new Error('Use o formato: nome = valor');
      variables[line.slice(0, pos).trim()] = line.slice(pos + 1).trim();
    }
    const data = await request(api('/state'), {method:'PUT', json:{turn:Number($('editTurn').value),
      phase:$('editPhase').value, board_notes:$('editNotes').value, variables}});
    receiveState(data); toast('Estado atualizado.');
  }));
  $('aiPropose').addEventListener('click', run(async () => {
    await request(api('/automa/propose'), {method:'POST'});
    toast('Proposta recebida. Revise antes de executar.');
  }));
  $('toggleManual').addEventListener('click', () => $('manualProposalForm').classList.toggle('hidden'));
  $('manualProposalForm').addEventListener('submit', run(async () => {
    if (!$('manualAction').value) throw new Error('Cadastre uma ação na configuração de regras.');
    await request(api('/automa/manual'), {method:'POST', json:{action:$('manualAction').value,
      spoken:$('manualSpeech').value || `Vou realizar a ação ${$('manualAction').value}.`,
      reason:'Ação configurada e selecionada manualmente pelo controlador.',
      player_steps:$('manualSteps').value.split(/\r?\n/).filter(Boolean),
      state_changes:[], uncertainties:[], confidence:'media'}});
    $('manualProposalForm').classList.add('hidden'); toast('Jogada manual pronta para confirmação.');
  }));
  $('voiceEnabled').addEventListener('change', () => {
    localStorage.setItem('automaforge_voice', $('voiceEnabled').checked ? 'yes' : 'no');
    if (!$('voiceEnabled').checked) {
      if (window.AutomaAndroid) window.AutomaAndroid.stopSpeech();
      if ('speechSynthesis' in window) speechSynthesis.cancel();
    }
  });
  $('repeatSpeech').addEventListener('click', () => {
    if (!room?.last_announcement) return toast('Ainda não há uma ação anunciada.', true);
    if (!$('voiceEnabled').checked) return toast('Ative “Falar as ações” antes de repetir.', true);
    speak(room.last_announcement.text);
  });
  $('analyzeBoard').addEventListener('click', run(async () => {
    await request(api('/board/analyze'), {method:'POST'});
    toast('Análise registrada; confira as observações.');
  }));
  $('clearCards').addEventListener('click', run(async () => {
    if (!confirm('Descartar todas as leituras privadas de cartas desta sala?')) return;
    await request(api('/cards/private'), {method:'DELETE'}); toast('Memória privada de cartas descartada.');
  }));

  $('startBoardCamera').addEventListener('click', run(async () => {
    await startCamera('board'); $('boardFeedback').textContent = 'Câmera ativa; enquadre todo o tabuleiro.';
  }));
  $('sendBoardPhoto').addEventListener('click', run(() => window.AutomaAndroid ? startCamera('board') : uploadBoardPhoto(false)));
  $('boardFile').addEventListener('change', run(async () => {
    const file = $('boardFile').files?.[0]; if (!file) return;
    const data = await uploadFile('/board/photo', file);
    $('boardFile').value = ''; $('boardFeedback').textContent = `Foto ${data.board_version} enviada ao servidor Android.`;
  }));
  $('boardAuto').addEventListener('change', autoBoardInterval);
  $('startCardsCamera').addEventListener('click', run(async () => {
    await startCamera('cards'); $('cardsFeedback').textContent = 'Câmera ativa: mantenha a prévia fora de vista.';
  }));
  $('sendPrivateCard').addEventListener('click', run(async () => {
    if (window.AutomaAndroid) { await startCamera('cards'); return; }
    const result = await uploadFile('/cards/private', await photoBlob('cardsVideo'));
    $('cardsFeedback').textContent = `Carta interpretada e guardada privadamente. Total: ${result.private_card_count}.` + (result.has_uncertainties ? ' A leitura teve incertezas: tente fotografar novamente se necessário.' : '');
    toast('Carta lida sem revelar seu conteúdo.');
  }));
  $('cardFile').addEventListener('change', run(async () => {
    if (!$('cardFile').files.length) return;
    const data = await uploadFile('/cards/private', $('cardFile').files[0], 'carta.jpg');
    $('cardFile').value = '';
    $('cardsFeedback').textContent = `Carta registrada privadamente. Total: ${data.private_card_count}.` + (data.has_uncertainties ? ' A leitura teve incertezas: confira o enquadramento.' : '');
    toast('Carta interpretada sem exibir o conteúdo.');
  }));
  window.addEventListener('beforeunload', () => { clearInterval(timer); clearInterval(processingTimer); stopCamera('board'); stopCamera('cards'); });

  (async () => {
    if (window.AutomaAndroid) {
      $('boardAutoRow').classList.add('hidden');
      $('startBoardCamera').textContent = 'Abrir câmera nativa automática';
      $('startCardsCamera').textContent = 'Abrir leitor privado nativo';
      $('sendBoardPhoto').textContent = 'Abrir câmera';
      $('sendPrivateCard').textContent = 'Abrir leitor de cartas';
    }
    try {
      health = await request('/api/health');
      status(health.ai_enabled ? 'Servidor local + IA' : 'Servidor local · modo manual', 'online');
      if (code) {
        const data = await request(`/api/rooms/${encodeURIComponent(code)}`);
        enterRoom(data);
      }
    } catch (error) {
      status('Servidor indisponível', 'error'); toast(error.message, true);
      code = ''; updateURL();
    }
  })();
})();
