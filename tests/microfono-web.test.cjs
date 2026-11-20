const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(require('node:path').join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const source = html.slice(html.indexOf('let fuenteMic='), html.indexOf('// ------------------------------------------------------------ conexión con el PC'));
function entorno({aviso='',fallo=false}={}) {
  const eventos=[];
  let revision=0,sondeo;
  const nodo=()=>({connect(){},disconnect(){}});
  const track={stop(){eventos.push('stop');}};
  const ctx={sampleRate:48000,createMediaStreamSource:nodo,createScriptProcessor:nodo,
    createGain:()=>({...nodo(),gain:{value:1}}),destination:{}};
  const s={console,Float32Array,Int16Array,ArrayBuffer,DataView,Blob,
    window:{isSecureContext:true},document:{addEventListener(){}},
    navigator:{mediaDevices:{async getUserMedia(){eventos.push('captura');if(fallo)throw Error('denegado');return {getTracks:()=>[track],getAudioTracks:()=>[track]};}}},
    JarvisNativo:{prepararMicrofono(){eventos.push('preparar');return aviso;},revisionMicrofono:()=>revision,
      pausarEscucha(si){eventos.push(si?'pausar':'reanudar');}},
    audio:()=>ctx,reproductor:{pause(){}},cola:[],nivelMic:0,
    boton:{disabled:false,classList:{add(){},remove(){}},textContent:''},auto:{checked:true},
    poner(){},anadir(){},pedir:async()=>({}),decir(){},manejarTelefono(){},
    setTimeout:()=>1,clearTimeout(){},setInterval(fn){sondeo=fn;return 2;},clearInterval(){}};
  vm.createContext(s);vm.runInContext(source,s);
  return {s,eventos,cambiar(){revision++;sondeo();},run:c=>vm.runInContext(c,s)};
}
(async()=>{
  const a=entorno();await a.s.empezar();
  assert.deepEqual(a.eventos.slice(0,2),['preparar','captura']);
  assert.equal(a.run('grabando'),true);
  a.cambiar();assert.equal(a.run('grabando'),false);
  assert.ok(a.eventos.includes('stop'));assert.ok(a.eventos.includes('reanudar'));
  const b=entorno({fallo:true});await b.s.empezar();
  assert.equal(b.run('grabando'),false);assert.ok(b.eventos.includes('reanudar'));assert.equal(b.s.boton.disabled,false);
  const c=entorno({aviso:'Permiso Bluetooth'});await c.s.empezar();
  assert.ok(!c.eventos.includes('captura'));assert.ok(c.eventos.includes('reanudar'));
  const d=entorno();await d.s.empezar();
  d.run('proc.onaudioprocess({inputBuffer:{getChannelData:()=>new Float32Array(4096).fill(.008)}})');
  assert.equal(d.run('hablo'),true);d.s.cancelarMicrofono();
  console.log('OK: orden de apertura, desconexión, permisos, limpieza y voz tenue.');
})().catch(e=>{console.error(e);process.exitCode=1;});
