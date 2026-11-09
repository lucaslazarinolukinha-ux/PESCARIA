// =========================================================
// PESCARIA - duelo de 2 jogadores
// =========================================================

// ---------- Estado ----------
let token = null;
let meuNome = "";
let emSala = false;
let ocupado = false;      // animação de pesca / descanso em andamento
let modalAberto = false;
let fimAberto = false;
let dadosFim = null;

// força / energia
let medindo = false;
let forcaAtual = 0;
let rafMedidor = null;
let energia = 100;
let cansado = false;
let cansadoPendente = false;

// sincronização com o servidor
let ultimoId = 0;          // última mensagem recebida
let sincronizando = false;
let fase = "";             // ESPERANDO | JOGANDO | TERMINADA
let fimEm = 0;             // quando a partida acaba (relógio do navegador)
let ultimoLago = "";

const FORCA_MAX_CANSADO = 40; // igual ao do Java
const PERIODO_MEDIDOR = 1300; // ms para o traço subir e descer uma vez
const ORIGEM_X = 310, ORIGEM_Y = 27, AGUA_Y = 150, ALCANCE = 3.2;
const TEXTO_PADRAO = "Aperte PESCAR e acerte o traço no vermelho para lançar longe!";

const EMOJI = { COMUM: "🐟", INCOMUM: "🐠", RARO: "🐡", EPICO: "🦈", LENDARIO: "🐋" };
const NOMES = { COMUM: "Comum", INCOMUM: "Incomum", RARO: "Raro", EPICO: "Épico", LENDARIO: "Lendário" };
const FAISCAS = { COMUM: 0, INCOMUM: 2, RARO: 5, EPICO: 8, LENDARIO: 14 };

const contagem = { COMUM: 0, INCOMUM: 0, RARO: 0, EPICO: 0, LENDARIO: 0 };
const descobertos = {}; // raridade -> nome do peixe já pescado

// ---------- Elementos ----------
const $ = (id) => document.getElementById(id);
const formLogin = $("form-login");
const inputNome = $("input-nome");
const erro = $("erro");
const euNome = $("eu-nome");
const euMoedas = $("eu-moedas");
const advNome = $("adv-nome");
const advMoedas = $("adv-moedas");
const tempoEl = $("tempo");
const lagoResumo = $("lago-resumo");
const wrap = $("cena-wrap");
const cena = $("cena");
const peixePulo = $("peixe-pulo");
const toastEl = $("toast");
const album = $("album");
const lagoEl = $("lago");
const historico = $("historico");
const ranking = $("ranking");
const chat = $("chat");
const formChat = $("form-chat");
const chatInput = $("chat-input");
const btnPescar = $("btn-pescar");
const btnDescansar = $("btn-descansar");
const btnSair = $("btn-sair");
const modal = $("modal");
const carta = $("carta");
const btnContinuar = $("btn-continuar");
const telaEspera = $("tela-espera");
const telaFim = $("tela-fim");

const linha = cena.querySelector(".linha");
const boia = cena.querySelector(".boia");
const splash = cena.querySelector(".splash");
const medidor = $("medidor");
const ponteiro = $("ponteiro");
const valorForca = $("valor-forca");
const medidorTexto = $("medidor-texto");
const medidorBloqueio = $("medidor-bloqueio");
const energiaBarra = $("energia-barra");
const energiaTexto = $("energia-texto");
const advEnergiaBarra = $("adv-energia-barra");
const advEnergiaTexto = $("adv-energia-texto");
const advAcao = $("adv-acao");
const cansacoEl = $("cansaco");

// ---------- Escala da cena (800x360 se adapta à largura da tela) ----------
function ajustarCena() {
  cena.style.transform = `scale(${wrap.clientWidth / 800})`;
}
new ResizeObserver(ajustarCena).observe(wrap);
ajustarCena();

// ---------- API ----------
async function chamarApi(acao, params) {
  const query = new URLSearchParams(params).toString();
  const resposta = await fetch(`/api/${acao}?${query}`, { method: "POST" });
  const dados = await resposta.json();
  if (!resposta.ok) {
    const e = new Error(dados.erro || "Erro no servidor");
    e.status = resposta.status;
    e.dados = dados;
    throw e;
  }
  return dados;
}

// ---------- Utilitários ----------
const esperar = (ms) => new Promise((r) => setTimeout(r, ms));

function animarNumero(el, para) {
  const de = parseInt(el.textContent) || 0;
  if (de === para) return;
  const inicio = performance.now();
  const duracao = 600;
  function passo(agora) {
    const t = Math.min((agora - inicio) / duracao, 1);
    el.textContent = Math.round(de + (para - de) * t);
    if (t < 1) requestAnimationFrame(passo);
  }
  requestAnimationFrame(passo);
}

function formatarTempo(ms) {
  const s = Math.ceil(ms / 1000);
  return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
}

function atualizarEnergia(valor, estaCansado) {
  energia = valor;
  cansado = estaCansado;
  energiaBarra.style.width = valor + "%";
  energiaBarra.classList.toggle("baixa", estaCansado);
  energiaTexto.textContent = valor + "/100";
  // quando cansado, a parte de cima (forte) da barra fica bloqueada
  medidorBloqueio.style.height = estaCansado ? (100 - FORCA_MAX_CANSADO) + "%" : "0";
}

function atualizarEnergiaAdv(valor, estaCansado) {
  advEnergiaBarra.style.width = valor + "%";
  advEnergiaBarra.classList.toggle("baixa", estaCansado);
  advEnergiaTexto.textContent = valor + "/100";
}

let toastTimer;
function toast(msg) {
  toastEl.textContent = msg;
  toastEl.classList.add("mostrar");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove("mostrar"), 2000);
}

function desenharAlbum() {
  album.innerHTML = Object.keys(EMOJI).map((r) => {
    const bloqueado = contagem[r] === 0;
    return `
      <div class="cartao r-${r} ${bloqueado ? "bloqueado" : ""}" id="cartao-${r}">
        <span class="c">${contagem[r]}</span>
        <span class="e">${EMOJI[r]}</span>
        <span class="n">${bloqueado ? "???" : descobertos[r]}</span>
        <span class="r">${NOMES[r]}</span>
      </div>`;
  }).join("");
}

function desenharLago(lago) {
  lagoEl.innerHTML = lago.map((l) => `
    <div class="cartao r-${l.raridade} ${l.restante === 0 ? "esgotado" : ""}">
      <span class="c">${l.restante}</span>
      <span class="e">${EMOJI[l.raridade]}</span>
      <span class="n">${l.nome}</span>
      <span class="r">${l.restante === 0 ? "Esgotado" : NOMES[l.raridade]}</span>
      <span class="v">Você ${l.voce} · Rival ${l.rival}</span>
    </div>`).join("");
}

function sincronizarAlbum(lago) {
  let mudou = false;
  lago.forEach((l) => {
    if (contagem[l.raridade] !== l.voce) { contagem[l.raridade] = l.voce; mudou = true; }
    if (l.voce > 0 && descobertos[l.raridade] !== l.nome) { descobertos[l.raridade] = l.nome; mudou = true; }
  });
  if (mudou) desenharAlbum();
}

function adicionarHistorico(raridade, html, valor) {
  const vazio = historico.querySelector(".vazio");
  if (vazio) vazio.remove();
  const li = document.createElement("li");
  if (raridade) li.className = "r-" + raridade;
  li.innerHTML = `<span>${html}</span><span class="valor">${valor || ""}</span>`;
  historico.prepend(li);
  while (historico.children.length > 30) historico.lastChild.remove();
}

function travar(valor, texto) {
  ocupado = valor;
  btnPescar.disabled = valor;
  btnDescansar.disabled = valor || medindo;
  btnPescar.textContent = valor
    ? (texto || "⏳ Pescando...")
    : (medindo ? "✋ LANÇAR!" : "🎣 PESCAR");
  if (!valor) aposLiberar();
}

// quando termina uma animação: mostra o fim da partida (se acabou) ou atualiza tudo
function aposLiberar() {
  if (!token) return;
  if (dadosFim && !fimAberto) mostrarFim();
  else sincronizar();
}

function resetarJogo() {
  Object.keys(contagem).forEach((r) => { contagem[r] = 0; delete descobertos[r]; });
  desenharAlbum();
  historico.innerHTML = '<li class="vazio">Nenhuma pescaria ainda</li>';
  chat.innerHTML = "";
  lagoEl.innerHTML = "";
  ultimoId = 0;
  ultimoLago = "";
  fase = "";
  dadosFim = null;
  fimAberto = false;
  euMoedas.textContent = "0";
  advMoedas.textContent = "0";
  advNome.textContent = "Aguardando...";
  advAcao.textContent = "";
  lagoResumo.textContent = "Tempo";
  tempoEl.textContent = formatarTempo(120000);
  tempoEl.classList.remove("urgente");
  atualizarEnergia(100, false);
  atualizarEnergiaAdv(100, false);
  posicionarLancamento(0);
  zerarMedidor();
  esconderCansaco();
}

function mostrarCansaco() { cansacoEl.classList.add("mostrar"); }
function esconderCansaco() { cansacoEl.classList.remove("mostrar"); }

// ---------- Lançamento visual (boia e linha vão mais longe com mais força) ----------
function posicionarLancamento(forca) {
  const x = ORIGEM_X + forca * ALCANCE;
  const dx = x - ORIGEM_X;
  const dy = AGUA_Y - ORIGEM_Y;
  const angulo = Math.atan2(dx, dy) * 180 / Math.PI;
  linha.style.height = Math.hypot(dx, dy) + "px";
  linha.style.transform = `rotate(${-angulo}deg)`;
  boia.style.left = (x - 10) + "px";
  splash.style.left = x + "px";
  peixePulo.style.left = x + "px";
}

function nomeDaZona(forca) {
  return forca < 35 ? "curto" : (forca < 70 ? "médio" : "longo");
}

// ---------- Minigame da força (barra vertical) ----------
function zerarMedidor() {
  ponteiro.style.bottom = "0%";
  valorForca.textContent = "0%";
}

function iniciarMedidor() {
  if (ocupado || medindo || !token) return;
  esconderCansaco();
  medindo = true;
  btnDescansar.disabled = true;
  btnPescar.textContent = "✋ LANÇAR!";
  medidor.classList.add("ativo");
  medidorTexto.textContent = cansado
    ? "Cansado... só dá para lançar com pouca força."
    : "Aperte ESPAÇO quando o traço estiver no vermelho!";

  const max = cansado ? FORCA_MAX_CANSADO : 100;
  const inicio = performance.now();

  function frame(agora) {
    const f = ((agora - inicio) % PERIODO_MEDIDOR) / PERIODO_MEDIDOR;
    const triangulo = 1 - Math.abs(2 * f - 1); // 0 -> 1 -> 0 (sobe e desce)
    forcaAtual = Math.round(triangulo * max);
    ponteiro.style.bottom = forcaAtual + "%";
    valorForca.textContent = forcaAtual + "%";
    rafMedidor = requestAnimationFrame(frame);
  }
  rafMedidor = requestAnimationFrame(frame);
}

function pararMedidor() {
  cancelAnimationFrame(rafMedidor);
  medindo = false;
  medidor.classList.remove("ativo");
}

function cancelarMedidor() {
  if (!medindo) return;
  pararMedidor();
  zerarMedidor();
  travar(false);
  medidorTexto.textContent = TEXTO_PADRAO;
}

// ---------- Carta do peixe (popup) ----------
function abrirModal(p) {
  carta.className = "carta r-" + p.raridade;
  $("carta-emoji").textContent = EMOJI[p.raridade];
  $("carta-nome").textContent = p.nome;
  $("carta-raridade").textContent = NOMES[p.raridade];
  $("carta-ganho").textContent = "🪙 +" + p.valor;

  const faiscas = $("faiscas");
  faiscas.innerHTML = "";
  for (let i = 0; i < FAISCAS[p.raridade]; i++) {
    const s = document.createElement("span");
    s.textContent = "✨";
    s.style.left = Math.random() * 90 + 3 + "%";
    s.style.top = Math.random() * 90 + 3 + "%";
    s.style.animationDelay = Math.random() * 1.2 + "s";
    faiscas.appendChild(s);
  }

  modal.classList.remove("oculto");
  modalAberto = true;
}

function fecharModal() {
  modal.classList.add("oculto");
  modalAberto = false;
  cena.classList.remove("fisgou", "lendario");
  posicionarLancamento(0);
  zerarMedidor();
  travar(false);
  medidorTexto.textContent = TEXTO_PADRAO;
  if (cansadoPendente && !fimAberto) {
    cansadoPendente = false;
    mostrarCansaco();
  }
}

btnContinuar.addEventListener("click", fecharModal);
modal.addEventListener("click", (e) => { if (e.target === modal) fecharModal(); });

// ---------- Ranking ----------
async function carregarRanking() {
  if (!token) return;
  try {
    const resposta = await fetch("/api/ranking");
    const lista = await resposta.json();
    ranking.innerHTML = "";

    if (!lista.length) {
      ranking.innerHTML = '<li class="vazio">Ninguém pescou ainda</li>';
      return;
    }

    lista.forEach((j) => {
      const li = document.createElement("li");
      if (j.nome.toLowerCase() === meuNome.toLowerCase()) li.className = "eu";

      const nome = document.createElement("span");
      nome.className = "nome";
      nome.textContent = j.nome; // textContent evita HTML malicioso

      const nivel = document.createElement("span");
      nivel.className = "nivel";
      nivel.textContent = "Nv " + j.nivel;

      const moedas = document.createElement("span");
      moedas.className = "moedas";
      moedas.textContent = "🪙 " + j.moedas;

      li.append(nome, nivel, moedas);
      ranking.appendChild(li);
    });
  } catch (e) {
    // sem conexão agora: tenta de novo no próximo ciclo
  }
}

// ---------- Entrar ----------
formLogin.addEventListener("submit", async (e) => {
  e.preventDefault();
  erro.textContent = "";
  try {
    const dados = await chamarApi("entrar", { nome: inputNome.value });
    token = dados.token;
    meuNome = dados.nome;
    resetarJogo();
    document.body.classList.add("logado");
    ajustarCena();
    const sala = await chamarApi("sala/entrar", { token });
    emSala = true;
    aplicarEstado(sala);
    carregarRanking();
  } catch (err) {
    token = null;
    emSala = false;
    document.body.classList.remove("logado");
    erro.textContent = err.message;
  }
});

// ---------- Sincronização com o servidor (memória compartilhada) ----------
async function sincronizar() {
  if (!token || !emSala || sincronizando) return;
  sincronizando = true;
  try {
    const d = await chamarApi("estado", { token, desde: ultimoId });
    aplicarEstado(d);
  } catch (err) {
    if (err.status === 401 || err.status === 409) {
      toast("Conexão com a partida perdida. Entre de novo.");
      sair();
    }
  } finally {
    sincronizando = false;
  }
}

function textoAcao(a) {
  if (a.ausente) return "📡 " + a.nome + " está sem conexão...";
  if (a.acao === "pescando") return "🎣 " + a.nome + " está pescando...";
  if (a.acao === "descansando") return "😴 " + a.nome + " está descansando...";
  return "👀 " + a.nome + " está observando o lago";
}

function aplicarEstado(d) {
  const pt = d.partida;

  // fase da partida
  if (pt.estado === "JOGANDO" && fase !== "JOGANDO") toast("⚔️ A partida começou! Vale 2 minutos.");
  fase = pt.estado;
  telaEspera.classList.toggle("oculto", fase !== "ESPERANDO");

  // relógio
  fimEm = Date.now() + pt.restante;
  if (fase !== "JOGANDO") {
    tempoEl.textContent = formatarTempo(pt.restante);
    tempoEl.classList.remove("urgente");
  }

  // adversário
  const a = d.adversario;
  if (a) {
    advNome.textContent = a.nome;
    animarNumero(advMoedas, a.moedas);
    atualizarEnergiaAdv(a.energia, a.cansado);
    advAcao.textContent = fase === "JOGANDO" ? textoAcao(a) : "";
  } else {
    advNome.textContent = "Aguardando...";
    advMoedas.textContent = "0";
    atualizarEnergiaAdv(100, false);
    advAcao.textContent = "";
  }

  // lago compartilhado
  const chaveLago = JSON.stringify(d.lago);
  if (chaveLago !== ultimoLago) {
    ultimoLago = chaveLago;
    desenharLago(d.lago);
  }
  const total = d.lago.reduce((soma, l) => soma + l.restante, 0);
  lagoResumo.textContent = "Tempo · 🐟 " + total + " no lago";

  // meus dados (não mexe enquanto a animação da minha pesca roda, para não dar spoiler)
  euNome.textContent = d.eu.nome + " (você)";
  if (!ocupado) {
    animarNumero(euMoedas, d.eu.moedas);
    atualizarEnergia(d.eu.energia, d.eu.cansado);
    sincronizarAlbum(d.lago);
  }

  // mensagens novas
  for (const m of d.mensagens) {
    if (ocupado && m.autor === meuNome && (m.tipo === "evento" || m.tipo === "raro")) break;
    adicionarMensagem(m);
    ultimoId = m.id;
  }

  // fim de jogo
  if (pt.estado === "TERMINADA") {
    dadosFim = d;
    if (!ocupado && !fimAberto) mostrarFim();
  }
}

function atualizarRelogio() {
  if (fase !== "JOGANDO") return;
  const restante = Math.max(0, fimEm - Date.now());
  tempoEl.textContent = formatarTempo(restante);
  tempoEl.classList.toggle("urgente", restante <= 15000);
}

// ---------- Chat e eventos ----------
function adicionarMensagem(m) {
  const li = document.createElement("li");
  li.className = "msg " + m.tipo + (m.autor === meuNome ? " minha" : "");

  if (m.tipo === "chat") {
    const quem = document.createElement("b");
    quem.textContent = m.autor + ": ";
    const texto = document.createElement("span");
    texto.textContent = m.texto;
    li.append(quem, texto);
  } else if (m.tipo === "sistema") {
    li.textContent = "📢 " + m.texto;
  } else if (m.tipo === "raro") {
    li.textContent = "✨ " + m.autor + " " + m.texto;
  } else {
    li.textContent = "• " + m.autor + " " + m.texto;
  }

  const colado = chat.scrollTop + chat.clientHeight >= chat.scrollHeight - 30;
  chat.appendChild(li);
  while (chat.children.length > 80) chat.firstChild.remove();
  if (colado) chat.scrollTop = chat.scrollHeight;
}

async function enviarMensagem(texto) {
  if (!token || !emSala) return;
  try {
    await chamarApi("msg", { token, texto });
    sincronizar();
  } catch (err) {
    toast("⚠️ " + err.message);
  }
}

formChat.addEventListener("submit", async (e) => {
  e.preventDefault();
  const texto = chatInput.value.trim();
  if (!texto) return;
  chatInput.value = "";
  await enviarMensagem(texto);
});

document.querySelectorAll(".rapida").forEach((b) =>
  b.addEventListener("click", () => {
    enviarMensagem(b.dataset.t);
    b.blur();
  }));

// ---------- Fim da partida ----------
function mostrarFim() {
  const d = dadosFim;
  if (!d) return;
  fimAberto = true;
  pararMedidor();
  zerarMedidor();
  esconderCansaco();
  travar(false);

  const venc = d.partida.vencedor;
  const titulo = $("fim-titulo");
  titulo.className = "fim-titulo";
  let bonus = "";
  if (venc === null) {
    titulo.textContent = "🤝 EMPATE!";
    titulo.classList.add("empate");
  } else if (venc === meuNome) {
    titulo.textContent = "🏆 VITÓRIA!";
    titulo.classList.add("vitoria");
    bonus = "+100 🪙 de bônus de vitória no seu perfil!";
  } else {
    titulo.textContent = "😢 DERROTA";
    titulo.classList.add("derrota");
  }

  $("fim-motivo").textContent = d.partida.motivo;
  $("fim-eu-nome").textContent = d.eu.nome;
  $("fim-eu-moedas").textContent = "🪙 " + d.eu.moedas;
  $("fim-adv-nome").textContent = d.adversario ? d.adversario.nome : "-";
  $("fim-adv-moedas").textContent = "🪙 " + (d.adversario ? d.adversario.moedas : 0);
  $("fim-bonus").textContent = bonus;
  telaFim.classList.remove("oculto");
  carregarRanking();
}

async function novaPartida() {
  telaFim.classList.add("oculto");
  resetarJogo();
  try {
    const d = await chamarApi("sala/entrar", { token });
    emSala = true;
    aplicarEstado(d);
  } catch (err) {
    toast("⚠️ " + err.message);
    sair();
  }
}

// ---------- Pescar (1º clique inicia a força, 2º clique lança) ----------
function pescar() {
  if (ocupado || !token || fimAberto) return;
  if (fase !== "JOGANDO") {
    toast("Aguarde o adversário entrar...");
    return;
  }
  if (document.activeElement) document.activeElement.blur();
  if (!medindo) {
    iniciarMedidor();
    return;
  }
  lancar(Math.max(10, forcaAtual));
}

async function lancar(forca) {
  pararMedidor();
  travar(true);
  ponteiro.style.bottom = forca + "%";
  valorForca.textContent = forca + "%";
  medidorTexto.textContent = "Lançamento " + nomeDaZona(forca) + "!";
  if (forca >= 90) toast("💥 Lançamento perfeito!");

  cena.classList.remove("fisgou", "lendario");
  posicionarLancamento(forca);
  cena.classList.add("pescando");

  try {
    const [dados] = await Promise.all([chamarApi("pescar", { token, forca }), esperar(1400)]);
    const p = dados.peixe;

    // o peixe fisga e pula da água
    cena.classList.remove("pescando");
    peixePulo.textContent = EMOJI[p.raridade];
    void cena.offsetWidth;
    cena.classList.add("fisgou");
    if (p.raridade === "LENDARIO") cena.classList.add("lendario");
    await esperar(800);

    // atualiza dados do jogo
    atualizarEnergia(dados.energia, dados.cansado);
    animarNumero(euMoedas, dados.moedas);
    cansadoPendente = dados.cansado;
    contagem[p.raridade]++;
    descobertos[p.raridade] = p.nome;
    desenharAlbum();
    $("cartao-" + p.raridade).classList.add("bump");
    adicionarHistorico(
      p.raridade,
      `<b>${p.nome}</b> · ${NOMES[p.raridade]} <small>(${nomeDaZona(dados.forca)}, ${dados.forca}%)</small>`,
      "+" + p.valor
    );

    abrirModal(p);
    carregarRanking();
  } catch (err) {
    cena.classList.remove("pescando");
    posicionarLancamento(0);
    zerarMedidor();
    if (err.dados && err.dados.energia !== undefined) {
      atualizarEnergia(err.dados.energia, err.dados.cansado);
      if (err.status === 409 && err.dados.cansado) mostrarCansaco();
    }
    toast("⚠️ " + err.message);
    medidorTexto.textContent = TEXTO_PADRAO;
    travar(false);
  }
}

// ---------- Descansar ----------
async function descansar() {
  if (ocupado || medindo || !token || fimAberto) return;
  if (fase !== "JOGANDO") {
    toast("Aguarde o adversário entrar...");
    return;
  }
  if (energia >= 100) {
    toast("Sua energia já está cheia!");
    return;
  }
  if (document.activeElement) document.activeElement.blur();
  travar(true, "😴 Descansando...");
  esconderCansaco();
  try {
    const [dados] = await Promise.all([chamarApi("descansar", { token }), esperar(1500)]);
    atualizarEnergia(dados.energia, dados.cansado);
    toast("😴 Você recuperou energia");
    adicionarHistorico(null, "Você descansou", "+⚡");
  } catch (err) {
    toast("⚠️ " + err.message);
  } finally {
    travar(false);
  }
}

// ---------- Sair ----------
function sair() {
  pararMedidor();
  zerarMedidor();
  if (token) chamarApi("sala/sair", { token }).catch(() => {});
  token = null;
  emSala = false;
  meuNome = "";
  fase = "";
  fimAberto = false;
  dadosFim = null;
  inputNome.value = "";
  modal.classList.add("oculto");
  modalAberto = false;
  telaEspera.classList.add("oculto");
  telaFim.classList.add("oculto");
  cansadoPendente = false;
  esconderCansaco();
  cena.classList.remove("pescando", "fisgou", "lendario");
  posicionarLancamento(0);
  travar(false);
  medidorTexto.textContent = TEXTO_PADRAO;
  document.body.classList.remove("logado");
  inputNome.focus();
}

btnPescar.addEventListener("click", pescar);
btnDescansar.addEventListener("click", descansar);
btnSair.addEventListener("click", sair);
$("btn-cancelar-espera").addEventListener("click", sair);
$("btn-sair-fim").addEventListener("click", sair);
$("btn-de-novo").addEventListener("click", novaPartida);
$("btn-descansar-cansaco").addEventListener("click", descansar);
$("btn-lancar-leve").addEventListener("click", () => {
  esconderCansaco();
  iniciarMedidor();
});

// ---------- Teclado ----------
document.addEventListener("keydown", (e) => {
  if (e.target.tagName === "INPUT") return;
  if (fimAberto) return;

  if (modalAberto) {
    if (["Space", "Enter", "Escape"].includes(e.code)) {
      e.preventDefault();
      fecharModal();
    }
    return;
  }

  if (!token) return;
  if (e.code === "Space") {
    e.preventDefault();
    if (!e.repeat) pescar();
  }
  if (e.code === "Escape") cancelarMedidor();
  if (e.key === "d" || e.key === "D") descansar();
});

// evita que o ESPAÇO "clique" em botões ao soltar a tecla
document.addEventListener("keyup", (e) => {
  if (e.code === "Space" && e.target.tagName !== "INPUT") e.preventDefault();
});

desenharAlbum();
setInterval(sincronizar, 1000);       // troca de mensagens + estado compartilhado
setInterval(atualizarRelogio, 250);   // relógio da partida
setInterval(carregarRanking, 15000);  // atualiza o ranking sozinho
inputNome.focus();