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
  let aiConfiguration = null;
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
  function updateURL() { history.replaceState(null, '', code ? `/?room=${code}&role=${role}` : '/'); if(window.AutomaAndroid?.sessionLocation)window.AutomaAndroid.sessionLocation(location.href); }

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
    if (text.trim().startsWith("[")) return JSON.parse(text);
    return text.split(/\r?\n/).map(line => {
      const parts = line.split('|').map(x => x.trim());
      return {name: parts[0], description: parts[1] || '', precondition: parts.slice(2).join('|') || ''};
    }).filter(x => x.name);
  }
  function actionLines(actions) {
    return JSON.stringify(actions || [], null, 2);
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
    const working=data.assist_task?.status==='running';$('aiPropose').disabled=working;
    $('assistProgress').classList.toggle('hidden',!data.assist_task);$('assistProgress').textContent=data.assist_task?.stage||'';
    $('selectedAiModel').textContent=aiSelectionText();
    if (!formIsBeingEdited('rulesForm')) fillRules(data.rules_draft || data);
    if (!formIsBeingEdited('stateForm')) fillState(data);
    if (oldActions !== JSON.stringify(data.actions) || !$('manualAction').options.length) fillManualActions();
    renderDraft(); renderProposal(); renderHistory(); renderObservation(); renderStructuredState(); renderKnowledge();
    refreshBoard(data.board_version);
    $('freezeBoard').textContent=data.board_frozen?'Liberar captura de nova foto':'Congelar foto para revisão';
    $('boardAge').textContent=`Foto ${data.board_version||0}${data.board_frozen?' · congelada':''}`;
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
    target.innerHTML = `<strong>Manual interpretado pela IA.</strong><p>As definições foram preenchidas para uso no fluxo automático. Você pode conferir fontes e corrigir algo nos campos opcionais.</p>` +
      `<strong>Pontos a verificar:</strong><ul>${(room.rules_draft.uncertainties || []).map(u => `<li>${esc(u)}</li>`).join('') || '<li>Sem dúvidas indicadas; ainda é necessária a revisão humana.</li>'}</ul>`;
  }
  function renderProposal() {
    const el = $('proposalArea'), p = room.proposal;
    if (!p) {
      el.className = 'proposal-empty';
      const d=room.decision;
      el.innerHTML=d ? `<strong>${esc(d.status)}</strong><p>${esc(d.message)}</p>`+
        `<ul>${(d.needed_data||[]).map(n=>`<li>Confirme ${esc(n.entity||'estado/cobertura')}: ${esc(n.field)}</li>`).join('')}${(d.errors||[]).map(e=>`<li>${esc(e)}</li>`).join('')}</ul>` : 'Nenhuma jogada pendente.';
      return;
    }
    const v=p.validation||{status:'unverifiable',errors:[],missing:['Proposta antiga. Solicite nova análise.']};
    const stale=p.state_id!==room.game_state.id||p.state_version!==room.game_state.version||p.rules_revision!==(room.rules_revision||0);
    const approved=p.execution_status==='approved';
    el.className='proposal';
    el.innerHTML=`<div class="eyebrow">${stale?'PROPOSTA DESATUALIZADA':approved?'APROVADA · EXECUÇÃO A CONFIRMAR':'PROPOSTA · REVISÃO HUMANA'}</div><h3>${esc(p.action)}</h3>`+
      `<p>Estado ${esc(p.state_version)} · ${esc(p.player)} · ${esc(p.phase)} / ${esc(p.substep)}</p>`+
      `<strong>Verificação local: ${esc(v.status)}</strong><ul>${[...(v.errors||[]),...(v.missing||[])].map(e=>`<li>${esc(e)}</li>`).join('')}</ul>`+
      `<ol>${(p.player_steps||[]).map((s,i)=>`<li><button type="button" class="text-button moveReference" data-index="${i}">${esc(s)}</button></li>`).join('')}</ol>`+
      `<p><strong>Justificativa:</strong> ${esc(p.reason)}</p>`+
      (!approved&&!stale&&v.status!=='invalid'?`<button class="primary" id="confirmProposal">Aprovar proposta e anunciar</button>`:'')+
      (approved&&!stale?`<label for="completedMoves">Quantos movimentos iniciais foram realmente executados?</label><input id="completedMoves" type="number" min="0" max="${p.moves.length}" value="${p.moves.length}"><button class="primary" id="executedProposal">Confirmar execução física (total ou parcial)</button>`:'')+
      `<button class="secondary" id="rejectProposal">Descartar proposta</button><p class="hint">Aprovar não altera o tabuleiro. Após execução parcial, o restante será recalculado a partir do estado atualizado. Se houve execução diferente, corrija o estado manualmente.</p>`;
    document.querySelectorAll('.moveReference').forEach(b=>b.addEventListener('click',()=>highlightMove(p.moves[Number(b.dataset.index)])));
    $('confirmProposal')?.addEventListener('click',run(async()=>{receiveState(await request(api('/automa/confirm'),{method:'POST',json:{proposal_id:p.id,acknowledge_unverified:v.status==='unverifiable'}}));toast('Proposta aprovada; confirme a execução depois de mover os componentes.');}));
    $('executedProposal')?.addEventListener('click',run(async()=>{receiveState(await request(api('/automa/executed'),{method:'POST',json:{proposal_id:p.id,completed_moves:Number($('completedMoves').value)}}));toast('Execução registrada. Confira o estado antes da próxima decisão.');}));
    $('rejectProposal').addEventListener('click',run(async()=>{receiveState(await request(api('/automa/reject'),{method:'POST',json:{proposal_id:p.id}}));}));
  }
  function editorState() { return JSON.parse($('structuredState').value); }
  function renderStructuredState() {
    const s=room.game_state;
    $('stateVersion').textContent=`Estado ${s.id} · versão ${s.version}. Jogador: ${s.active_player||'desconhecido'} · subetapa: ${s.substep||'não informada'}`;
    if(document.activeElement!==$('structuredState')&&!$('structuredState').dataset.dirty) $('structuredState').value=JSON.stringify(s,null,2);
    const positions=s.entities.filter(e=>['position','group'].includes(e.kind));
    const selected=$('calibrationPosition').value;
    $('calibrationPosition').innerHTML='<option value="">Selecione uma posição</option>'+positions.map(e=>`<option value="${esc(e.id)}">${esc(e.id)} · ${esc(e.owner||'compartilhado')}</option>`).join('');
    $('calibrationPosition').value=selected;
    $('boardReferences').innerHTML=positions.map(e=>`<button class="secondary small" type="button" data-position="${esc(e.id)}">${esc(e.id)} · ${esc(e.status)} · topo ${Math.max(0,...s.entities.filter(c=>c.location===e.id&&c.quantity>0).map(c=>c.level||0))}</button>`).join('');
    $('boardReferences').querySelectorAll('button').forEach(b=>b.addEventListener('click',()=>highlightIds([b.dataset.position])));
    drawMarkers(s);
  }
  function drawMarkers(s,ids=[]) {
    document.querySelectorAll('.board-marker,.board-region').forEach(e=>e.remove());
    const o=room?.board_observation;
    if(o?.photo_version===room?.board_version)for(const a of o.layout?.areas||[]) {
      if(!Array.isArray(a.region)||a.region.length!==4)continue;
      const b=document.createElement('button');b.type='button';b.className='board-region';b.textContent=a.id;
      const [x,y,w,h]=a.region;b.style.left=`${x*100}%`;b.style.top=`${y*100}%`;b.style.width=`${w*100}%`;b.style.height=`${h*100}%`;b.title=a.label;
      b.addEventListener('click',ev=>{ev.stopPropagation();$('recognitionReview').scrollIntoView({behavior:'smooth'});});$('boardFrame').appendChild(b);
    }
    if(!s)return;
    s.entities.filter(e=>['position','group'].includes(e.kind)&&Number.isFinite(e.x)&&Number.isFinite(e.y)&&e.x>=0&&e.x<=1&&e.y>=0&&e.y<=1&&e.image_version===room.board_version).forEach(e=>{
      const b=document.createElement('button');b.type='button';b.className='board-marker'+(ids.includes(e.id)?' highlighted':'');b.textContent=e.id;
      b.style.left=`${100*e.x}%`;b.style.top=`${100*e.y}%`;b.title=e.id+' · '+e.status;
      b.addEventListener('click',ev=>{ev.stopPropagation();$('calibrationPosition').value=e.id;});$('boardFrame').appendChild(b);
    });
  }
  function highlightIds(ids) { drawMarkers(room.game_state,ids);$('boardFrame').scrollIntoView({behavior:'smooth',block:'center'}); }
  function highlightMove(m) { highlightIds([m.source,m.destination]); }
  $('addStateEntity').addEventListener('click',run(async()=>{
    const s=editorState(),kind=$('newEntityKind').value;
    let n=1;while(s.entities.some(e=>e.id===`${kind}-${n}`))n++;
    s.entities.push({id:`${kind}-${n}`,kind,type:'',owner:'',visibility:'public',location:'',group:'',quantity:0,level:0,
      identity:kind==='component'?'pooled':'not_applicable',status:'unknown',source:'human',occupancy_known:false,
      topology:'none',neighbors:[],attributes:[],x:-1,y:-1});
    $('structuredState').value=JSON.stringify(s,null,2);$('structuredState').dataset.dirty='yes';
    toast('Entidade adicionada. Preencha localização, tipo, quantidade e situação antes de confirmar.');
  }));
  $('structuredState').addEventListener('input' ,()=>{$('structuredState').dataset.dirty='yes';});
  $('boardImage').addEventListener('click',event=>{
    const id=$('calibrationPosition').value;if(!id)return toast('Selecione uma posição para calibrar.',true);
    try {
      const s=editorState(),e=s.entities.find(e=>e.id===id),rect=event.currentTarget.getBoundingClientRect();
      if(!e)throw new Error('Posição não encontrada no editor.');
      e.x=(event.clientX-rect.left)/rect.width;e.y=(event.clientY-rect.top)/rect.height;
      e.image_version=room.board_version;e.source='human_calibration';
      $('structuredState').value=JSON.stringify(s,null,2);$('structuredState').dataset.dirty='yes';drawMarkers(s);
      toast('Referência marcada. Confirme o estado corrigido para salvar.');
    }catch(error){toast(error.message,true);}
  });
  $('saveStructuredState').addEventListener('click',run(async()=>{
    const data=await request(api('/state/structured'),{method:'PUT',json:editorState()});
    delete $('structuredState').dataset.dirty;receiveState(data);toast('Estado público confirmado.');
  }));
  $('reviewObservation').addEventListener('click',run(async()=>{
    const o=room.board_observation;if(!o)throw new Error('Analise uma imagem primeiro.');
    if(o.photo_version!==room.board_version||o.state_version!==room.game_state.version)throw new Error('Análise desatualizada. Capture/analise novamente.');
    const s=editorState(),conflicts=[];
    for(const e of o.entities||[]) {
      const existing=s.entities.find(x=>x.id===e.id);
      const anchor=s.entities.find(x=>x.kind===e.kind&&x.type===e.type&&x.location===e.location&&x.group===e.group&&x.level===e.level&&e.identity==='pooled');
      if(existing||anchor){conflicts.push(e.id);continue;}
      s.entities.push({...e});
    }
    $('structuredState').value=JSON.stringify(s,null,2);$('structuredState').dataset.dirty='yes';
    $('stateEditorHelp').textContent=`Candidatos adicionados para conferência. Entidades preservadas que precisam de comparação manual: ${conflicts.join(', ')||'nenhuma'}. Confira a observação abaixo e confirme somente após revisar.`;
  }));
  function renderHistory() {
    const el = $('historyArea');
    if (!room.history.length) { el.innerHTML = 'Sem jogadas aprovadas.'; return; }
    el.innerHTML = room.history.slice(-12).reverse().map(h =>
      `<div class="history-entry"><span class="history-turn"># ${esc(h.turn)}</span><div><strong>${esc(h.action)}</strong><p>${esc(h.spoken)}</p></div></div>`).join('');
  }
  function renderObservation() {
    const o=room.board_observation;
    $('observationArea').innerHTML=!o?'Analise uma foto pública ou informe o estado manualmente.':
      `<strong>Foto ${esc(o.photo_version)} · ${o.photo_version!==room.board_version||o.state_version!==room.game_state.version?'ANÁLISE DESATUALIZADA':o.automatically_applied?'estado preenchido automaticamente':'leitura disponível para correção'}</strong>`+
      `<p>${o.sufficient?'IA declarou cobertura suficiente; exige conferência.':'Foto insuficiente: revise cobertura, oclusões, pilhas e calibração.'}</p>`+
      `<ul>${[...(o.observations||[]),...(o.uncertainties||[]),...(o.conflicts||[])].map(x=>`<li>${esc(x)}</li>`).join('')}</ul>`+
      `<details><summary>Comparar entidades reconhecidas</summary><pre>${esc(JSON.stringify(o.entities||[],null,2))}</pre></details>`;
  }
  let reviewEntities=[],reviewFacts=[],modelPreview=null,modelActionsPreview=null;
  const statuses=['confirmed','observed','inferred','unknown'];
  const selectOptions=(values,current)=>values.map(v=>`<option value="${esc(v)}"${v===current?' selected':''}>${esc(v)}</option>`).join('');
  function typedInput(type,value,options=[],attrs='') {
    if(type==='boolean')return `<select ${attrs}>${selectOptions(['','true','false'],value===true?'true':value===false?'false':'')}</select>`;
    if(type==='enum'||type==='choice')return `<select ${attrs}>${selectOptions(['',...options],value==null?'':String(value))}</select>`;
    return `<input ${attrs} type="${['integer','number'].includes(type)?'number':'text'}" ${type==='integer'?'step="1"':''} value="${esc(Array.isArray(value)?value.join(', '):value==null?'':value)}">`;
  }
  function parseTyped(type,raw) {
    if(raw==='')return null;
    if(type==='boolean')return raw==='true';
    if(['integer','number'].includes(type)){const n=Number(raw);if(!Number.isFinite(n)||(type==='integer'&&!Number.isInteger(n)))throw new Error('Informe um número válido.');return n;}
    if(type==='entity_list')return raw.split(',').map(v=>v.trim()).filter(Boolean);
    return raw;
  }
  function renderKnowledge() {
    const s=room.game_state,m=room.state_model||{},candidate=room.state_model_draft||(m.reviewed?m:room.rules_draft?.state_model||m);
    if(!formIsBeingEdited('gameModelPanel')&&!$('stateModelJson').dataset.dirty) {
      $('stateModelPreview').dataset.version=s.version;$('stateModelPreview').dataset.rules=room.rules_revision||0;
      modelPreview=JSON.parse(JSON.stringify(candidate));modelActionsPreview=room.actions_draft||null;$('stateModelJson').value=JSON.stringify(candidate,null,2);
      $('stateModelPreview').innerHTML=`<strong>${candidate.reviewed?'Modelo conferido por você':'Modelo identificado pela IA'}</strong><p>${esc(candidate.summary||'Importe o manual; o app identifica os dados durante a leitura ou ao sugerir a jogada.')}</p>`+
        `<h4>Áreas reconhecíveis</h4><ul>${(candidate.areas||[]).map(a=>`<li><strong>${esc(a.label)}</strong> · ${esc(a.scope)} · ${esc(a.topology)}<br>${esc(a.visual_cues)}</li>`).join('')}</ul>`+
        `<h4>Variáveis deste jogo</h4><ul>${(candidate.fields||[]).map(f=>`<li><strong>${esc(f.label)}</strong> · ${esc(f.type)} · ${esc(f.scope)} · ${esc(f.visibility)}<br>${esc(f.description)}<br>Necessária para: ${esc((f.required_for_actions||[]).join(', ')||'consulta')}; fase: ${esc((f.required_phases||[]).join(', ')||'conforme ação')}</li>`).join('')}</ul>`+
        (modelActionsPreview?`<h4>Ações parametrizadas propostas</h4><ul>${modelActionsPreview.map(a=>`<li><strong>${esc(a.name)}</strong>: ${esc(a.description)}<br>Parâmetros: ${esc((a.parameters||[]).map(p=>p.name+(p.required?' (obrigatório)':'')).join(', ')||'nenhum adicional')}<br>Origens: ${esc((a.sources||[]).join(', '))}; destinos: ${esc((a.destinations||[]).join(', '))}<br>Restrições: ${esc((a.constraints||[]).map(c=>c.kind+': '+c.value).join('; '))}. Cobertura de legalidade continua não verificada.</li>`).join('')}</ul>`:'');
    }
    if(!$('factsForm').dataset.dirty&&!formIsBeingEdited('factsForm')) {
      $('factsForm').dataset.version=s.version;$('factsForm').dataset.rules=room.rules_revision||0;
      $('activePlayerInput').value=s.active_player||'';$('automaPlayerInput').value=s.automa_player||'';
      $('knowledgePhase').value=s.phase||'';$('knowledgeSubstep').value=s.substep||'';
      const es=s.entities||[],rows=[];
      for(const f of m.fields||[]) {
        if(f.visibility!=='public')continue;
        const subjects=f.scope==='game'?['game']:f.scope==='player'?[...new Set([s.active_player,s.automa_player,...es.map(e=>e.owner)].filter(v=>v&&v!=='shared'))]:es.filter(e=>['board','region','group'].includes(e.kind)).map(e=>e.id);
        for(const subject of subjects){const v=(s.facts||[]).find(v=>v.key===f.key&&v.subject===subject);rows.push(`<div class="game-field" data-key="${esc(f.key)}" data-subject="${esc(subject)}" data-type="${esc(f.type)}"><label>${esc(f.label)} · ${esc(subject)}</label>${typedInput(f.type,v?.value,f.options||[],'class="fact-value"')}<span class="hint">${esc(v?.status||'unknown')} · ${esc(f.description)}</span><label><input class="fact-unknown" type="checkbox"> Não sei / manter desconhecido</label></div>`);}
      }
      $('gameFieldsArea').innerHTML=rows.join('')||'<p class="hint">Os campos aparecem após confirmar o modelo e identificar os jogadores/áreas.</p>';
      $('gameFieldsArea').querySelectorAll('.game-field').forEach(row=>row.addEventListener('input',()=>{row.dataset.dirty='yes';$('factsForm').dataset.dirty='yes';}));
    }
    renderQuestions();renderRecognition();
  }
  $('stateModelJson').addEventListener('input',()=>{$('stateModelJson').dataset.dirty='yes';});
  $('factsForm').addEventListener('input',()=>{$('factsForm').dataset.dirty='yes';});
  function aiSelectionText() {
    return `Modelo de IA: ${health?.model||'configurado no controlador'} · Raciocínio: ${health?.reasoning_label||'Padrão do modelo'}`;
  }
  function fillReasoningSelect(preferred) {
    const model=aiConfiguration.model_options.find(m=>m.id===$('aiModelSelect').value);
    const options=model?.reasoning_options||[{value:'default',label:'Padrão do modelo'}];
    $('aiReasoningSelect').innerHTML=options.map(o=>`<option value="${esc(o.value)}">${esc(o.label)}</option>`).join('');
    $('aiReasoningSelect').value=options.some(o=>o.value===preferred)?preferred:'default';
    $('aiReasoningSelect').disabled=!aiConfiguration.can_configure||options.length===1;
  }
  function fillAiSettings(cfg) {
    aiConfiguration=cfg;
    $('aiModelSelect').innerHTML=cfg.model_options.map(m=>`<option value="${esc(m.id)}">${esc(m.label)}</option>`).join('');
    $('aiModelSelect').value=cfg.model;$('aiModelSelect').disabled=!cfg.can_configure;
    fillReasoningSelect(cfg.reasoning_effort);$('saveAiModel').disabled=!cfg.can_configure;
    $('aiSettingsMessage').textContent=cfg.can_configure?'Escolha nos menus e salve. As escolhas são mantidas ao reiniciar.':'Altere os menus no botão IA do controlador. Esta tela mostra as escolhas atuais.';
  }
  $('aiModelSelect').addEventListener('change',()=>fillReasoningSelect($('aiReasoningSelect').value));
  $('openAiSettings').addEventListener('click',run(async()=>{
    if(window.AutomaAndroid&&location.hostname==='127.0.0.1'){window.AutomaAndroid.configureAi();return;}
    const cfg=await request('/api/ai/settings');fillAiSettings(cfg);$('aiSettingsPanel').classList.remove('hidden');
  }));
  $('aiSettingsForm').addEventListener('submit',run(async()=>{
    await request('/api/ai/settings',{method:'PUT',json:{model:$('aiModelSelect').value,reasoning_effort:$('aiReasoningSelect').value}});
    health=await request('/api/health');$('selectedAiModel').textContent=aiSelectionText();toast('Modelo e raciocínio salvos. A próxima análise usará essas escolhas.');
  }));
  $('discoverStateModel').addEventListener('click',run(async()=>{
    receiveState(await request(api('/state/model/discover'),{method:'POST',json:{}}));toast('Modelo proposto a partir das regras. Revise antes de confirmar.');
  }));
  $('approveStateModel').addEventListener('click',run(async()=>{
    if(!$('stateModelReviewed').checked)throw new Error('Confirme a revisão do modelo.');
    const m=$('stateModelJson').dataset.dirty?JSON.parse($('stateModelJson').value):modelPreview;
    if(!m?.fields||!m?.areas)throw new Error('Identifique primeiro o modelo deste jogo.');
    const data=await request(api('/state/model'),{method:'PUT',json:{state_model:m,...(modelActionsPreview?{actions:modelActionsPreview}:{}),human_validated:true,state_version:Number($('stateModelPreview').dataset.version),rules_revision:Number($('stateModelPreview').dataset.rules)}});
    delete $('stateModelJson').dataset.dirty;$('stateModelReviewed').checked=false;receiveState(data);toast('Modelo confirmado; informe os valores da partida.');
  }));
  $('factsForm').addEventListener('submit',run(async()=>{
    const facts=[...$('gameFieldsArea').querySelectorAll('.game-field[data-dirty]')].map(row=>({key:row.dataset.key,subject:row.dataset.subject,value:row.querySelector('.fact-unknown').checked?null:parseTyped(row.dataset.type,row.querySelector('.fact-value').value),status:row.querySelector('.fact-unknown').checked?'unknown':'confirmed'}));
    const data=await request(api('/state/facts'),{method:'PUT',json:{state_version:Number($('factsForm').dataset.version),rules_revision:Number($('factsForm').dataset.rules),facts,
      active_player:$('activePlayerInput').value.trim(),automa_player:$('automaPlayerInput').value.trim(),phase:$('knowledgePhase').value.trim(),substep:$('knowledgeSubstep').value.trim()}});
    delete $('factsForm').dataset.dirty;receiveState(data);toast('Informações confirmadas salvas.');
  }));
  function renderQuestions() {
    if($('questionsArea').contains(document.activeElement)||$('questionsArea').dataset.dirty)return;
    const qs=(room.questions||[]).filter(q=>q.visibility!=='private'),pending=qs.filter(q=>q.status!=='answered');
    const needsAnswer=room.decision?.status==='needs_data'||room.assist_task?.status==='error';
    const current=pending.filter(q=>q.rules_revision===(room.rules_revision||0)&&(q.scope==='rules'||q.state_version===room.game_state.version));
    const primary=needsAnswer?current.find(q=>q.required):null;
    const cards=pending.map((q,i)=>{
      const stale=q.rules_revision!==(room.rules_revision||0)||(q.scope!=='rules'&&q.state_version!==room.game_state.version);
      const effectiveType=room.state_model?.fields?.find(f=>f.key===q.field)?.type||q.input_type;
      return `<div class="question-card" data-id="${esc(q.id)}" data-version="${room.game_state.version}" data-rules="${room.rules_revision||0}"><strong>${esc(q.prompt)}</strong><p class="hint">${esc(q.scope)} · ${q.required?'relevante à próxima etapa':'esclarecimento'}${stale?' · desatualizada: atualize a análise':''}${q.status==='unknown'?' · ainda não resolvida':''}</p>${typedInput(effectiveType,q.answer,q.options||[],'class="question-value" aria-label="Resposta à dúvida"')}<div class="button-row"><button class="primary answer-question" type="button" ${stale?'disabled':''}>Enviar resposta</button><button class="secondary unknown-question" type="button" ${stale?'disabled':''}>Não sei</button></div></div>`;
    });
    const main=primary?cards[pending.indexOf(primary)]:`<p>${needsAnswer?'Confira o dado indicado na análise ou melhore a foto.':'A IA tentará resolver os dados ao identificar e sugerir a jogada. Você só precisa responder se algo essencial continuar ambíguo.'}</p>`;
    $('questionsArea').innerHTML=main+(pending.some(q=>q!==primary)?`<details><summary>Outros esclarecimentos (opcional)</summary>${cards.filter((_,i)=>pending[i]!==primary).join('')}</details>`:'');
    $('questionsArea').insertAdjacentHTML('beforeend',`<details><summary>Respostas registradas (${(room.clarifications||[]).length})</summary>${(room.clarifications||[]).slice(-15).map(a=>`<p><strong>${esc(a.question)}</strong><br>${esc(a.status==='unknown'?'Não sei':Array.isArray(a.value)?a.value.join(', '):a.value)}</p>`).join('')}</details>`);
    $('questionsArea').querySelectorAll('.question-card').forEach(card=>{
      card.querySelector('.question-value').addEventListener('input',()=>{$('questionsArea').dataset.dirty='yes';});
      const send=unknown=>run(async()=>{
        const q=qs.find(q=>q.id===card.dataset.id),value=unknown?null:parseTyped(room.state_model?.fields?.find(f=>f.key===q.field)?.type||q.input_type,card.querySelector('.question-value').value);
        const data=await request(api('/questions/answer'),{method:'POST',json:{question_id:q.id,value,unknown,state_version:Number(card.dataset.version),rules_revision:Number(card.dataset.rules)}});
        delete $('questionsArea').dataset.dirty;receiveState(data);toast(unknown?'Pendência mantida.':'Resposta registrada. Reanalise a foto ou solicite nova decisão.');
      });
      card.querySelector('.answer-question').addEventListener('click',send(false));card.querySelector('.unknown-question').addEventListener('click',send(true));
    });
  }
  $('refreshQuestions').addEventListener('click',run(async()=>{
    await request(api('/questions'));delete $('questionsArea').dataset.dirty;receiveState(await request(api('')));
  }));
  $('reanalyzeAnswers').addEventListener('click',run(async()=>{
    await request(api('/board/analyze'),{method:'POST'});toast('Foto reanalisada com as respostas confirmadas.');
  }));
  $('reconsiderRules').addEventListener('click',run(async()=>{
    await request(api('/rules/interpret'),{method:'POST',json:{mode:$('manualMode').value,retry_uncertain:$('retryUncertain').checked,original_fallback:$('originalFallback').checked}});await refreshProcessing();toast('Interpretação iniciada com seus esclarecimentos.');
  }));
  function renderRecognition() {
    if($('recognitionTable').dataset.dirty||$('recognitionTable').contains(document.activeElement))return;
    const o=room.board_observation,s=room.game_state;
    if(!o){$('recognitionTable').innerHTML='<p class="hint">Analise uma foto para conferir as áreas e os componentes em campos editáveis.</p>';return;}
    const stale=o.photo_version!==room.board_version||o.state_version!==s.version;
    $('recognitionTable').dataset.version=s.version;$('recognitionTable').dataset.photo=room.board_version;
    reviewEntities=(o.entities||[]).map(e=>{const known=s.entities.find(x=>x.id===e.id);return known?.human_locked?JSON.parse(JSON.stringify(known)):JSON.parse(JSON.stringify(e));});
    for(const e of s.entities)if(!reviewEntities.some(c=>c.id===e.id))reviewEntities.push(JSON.parse(JSON.stringify(e)));
    const defs=room.state_model?.fields||[];reviewFacts=(o.facts||[]).filter(f=>defs.some(d=>d.key===f.key&&d.visibility==='public'));
    $('recognitionTable').innerHTML=`${stale?'<p class="risk">Esta análise está desatualizada. Reanalise antes de confirmar.</p>':''}<p class="hint">${o.manual_reference_count||0} referências do manual · ${o.crop_count||0} recortes · ${o.analysis_passes||1} etapas de reconhecimento</p>`+
      reviewEntities.map((e,i)=>`<details class="entity-row" data-index="${i}" ${['board','region','group'].includes(e.kind)?'open':''}><summary>${esc(e.id)} · ${esc(e.type||e.kind)} · ${esc(e.owner||'dono desconhecido')}</summary><label><input class="entity-use" type="checkbox" checked> Incluir esta entidade no estado revisado</label><div class="two-cols"><div><label>Tipo/descrição</label><input class="entity-type" value="${esc(e.type)}"></div><div><label>Dono (ID; shared = compartilhado)</label><input class="entity-owner" value="${esc(e.owner)}"></div></div><label>Função da área</label><select class="entity-area">${selectOptions(['',...(room.state_model?.areas||[]).map(a=>a.key)],e.area_type||'')}</select><div class="two-cols"><div><label>Localização (ID)</label><input class="entity-location" value="${esc(e.location)}"></div><div><label>Grupo (ID)</label><input class="entity-group" value="${esc(e.group)}"></div><div><label>Quantidade</label><input class="entity-quantity" type="number" min="0" value="${e.quantity||0}"></div><div><label>Nível da pilha</label><input class="entity-level" type="number" min="0" value="${e.level||0}"></div></div><label>Situação</label><select class="entity-status">${selectOptions(statuses,e.status||'unknown')}</select><label><input class="entity-occupancy" type="checkbox" ${e.occupancy_known?'checked':''}> Ocupação desta posição foi conferida, inclusive se vazia</label>${e.human_locked?'<p class="hint">Correção humana preservada.</p>':''}</details>`).join('')+
      reviewFacts.map((f,i)=>{const d=defs.find(d=>d.key===f.key);return `<div class="observed-fact" data-index="${i}"><label><input class="observed-use" type="checkbox"> Usar observação: ${esc(d.label)} · ${esc(f.subject)}</label>${typedInput(d.type,f.value,d.options||[],'class="observed-value"')}<p class="hint">Situação visual: ${esc(f.status)}. Confira antes de usar.</p></div>`;}).join('');
    $('confirmRecognition').disabled=stale;
    $('recognitionTable').querySelectorAll('input,select').forEach(input=>input.addEventListener('input',()=>{$('recognitionTable').dataset.dirty='yes';}));
  }
  $('confirmRecognition').addEventListener('click',run(async()=>{
    if(!room.board_observation)throw new Error('Analise a foto primeiro.');
    const s=JSON.parse(JSON.stringify(room.game_state)),entities=[];
    for(const row of $('recognitionTable').querySelectorAll('.entity-row')) {
      if(!row.querySelector('.entity-use').checked)continue;
      const e=reviewEntities[Number(row.dataset.index)];
      const quantity=Number(row.querySelector('.entity-quantity').value),level=Number(row.querySelector('.entity-level').value);
      if(!Number.isInteger(quantity)||!Number.isInteger(level)||quantity<0||level<0)throw new Error('Quantidade e nível precisam ser inteiros não negativos.');
      entities.push({...e,type:row.querySelector('.entity-type').value,owner:row.querySelector('.entity-owner').value.trim(),area_type:row.querySelector('.entity-area').value,
        location:row.querySelector('.entity-location').value.trim(),group:row.querySelector('.entity-group').value.trim(),quantity,level,status:row.querySelector('.entity-status').value,occupancy_known:row.querySelector('.entity-occupancy').checked});
    }
    s.entities=entities;s.version=Number($('recognitionTable').dataset.version);s.facts=s.facts||[];
    for(const row of $('recognitionTable').querySelectorAll('.observed-fact'))if(row.querySelector('.observed-use').checked) {
      const f=reviewFacts[Number(row.dataset.index)],d=room.state_model.fields.find(d=>d.key===f.key),current=s.facts.find(x=>x.key===f.key&&x.subject===f.subject);
      const next={...f,value:parseTyped(d.type,row.querySelector('.observed-value').value),status:'confirmed',source:'human_review',human_locked:true};
      if(current)Object.assign(current,next);else s.facts.push(next);
    }
    const data=await request(api('/board/confirm-state'),{method:'POST',json:{photo_version:Number($('recognitionTable').dataset.photo),state:s}});
    delete $('recognitionTable').dataset.dirty;delete $('structuredState').dataset.dirty;receiveState(data);toast('Leitura revisada confirmada no estado da partida.');
  }));

  async function photoBlob(videoId) {
    const video = $(videoId);
    if (!video.videoWidth || !video.videoHeight) throw new Error('Ative a câmera antes de enviar uma fotografia.');
    const canvas = document.createElement('canvas');
    const scale = Math.min(1, 2560 / Math.max(video.videoWidth, video.videoHeight));
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
    await request(api('/rules/interpret'),{method:'POST',json:{mode:$('manualMode').value,retry_uncertain:false,original_fallback:$('originalFallback').checked}});
    await refreshProcessing();toast('Manual importado; a IA vai preencher regras e dados do jogo.');
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
        `Modo: ${m.processingMode||'anterior'}. Anexos confirmados/tentados: ${m.imagesTransmitted || 0}/${m.imagesAttempted || 0}. ` +
        `PDF preparado: ${bytes(m.preparedPdfBytes)}; ${m.pdfPackets||0} pacotes (${bytes(m.pdfPacketBytes)}); imagens reprocessadas: ${m.imagesRecompressed||0}. ` +
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
    await request(api('/rules/interpret'), {method:'POST', json:{retry_uncertain:$('retryUncertain').checked, original_fallback:$('originalFallback').checked,mode:$('manualMode').value}});
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
    await request(api('/automa/assist'), {method:'POST'});
    toast('Análise concluída. Confira a jogada ou o dado que falta.');
  }));
  $('toggleManual').addEventListener('click', () => $('manualProposalForm').classList.toggle('hidden'));
  $('manualProposalForm').addEventListener('submit', run(async () => {
    if (!$('manualAction').value) throw new Error('Cadastre uma ação na configuração de regras.');
    await request(api('/automa/manual'), {method:'POST', json:{status:'proposed',state_id:room.game_state.id,
      state_version:room.game_state.version,rules_revision:room.rules_revision||0,player:room.game_state.active_player,
      phase:room.game_state.phase,substep:room.game_state.substep,action:$('manualAction').value,
      reason:'Jogada informada pelo controlador.',parameters:JSON.parse($('manualParameters').value),
      moves:JSON.parse($('manualSteps').value),needed_data:[]}});
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
  $('freezeBoard').addEventListener('click',run(async()=>{
    receiveState(await request(api('/board/freeze'),{method:'POST',json:{frozen:!room.board_frozen}}));
  }));
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
      $('runtimeVersion').textContent=`App ${health.app_version||'anterior'} · Interface 0.8`;
      if(health.state_contract!==3)toast('O servidor conectado usa outra versão. Confira APK, endereço do controlador e recarregue a interface.',true);
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
