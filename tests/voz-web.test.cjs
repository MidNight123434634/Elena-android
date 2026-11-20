const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/index.html'),'utf8');
const source=html.slice(html.indexOf('function decir(respuesta,audios)'),html.indexOf('function nivelVoz()'));
let telefono=0,pc=0;
const contexto={voz:{checked:true},rapida:{checked:false},JarvisNativo:{enLlamada:()=>false},
  cola:[],siguiente(){pc++;},hablarTelefono(){telefono++;}};
vm.createContext(contexto);vm.runInContext(source,contexto);
contexto.decir('Hola',[{tipo:'audio/wav',datos:'audio'}]);assert.equal(pc,1);assert.equal(telefono,0);
contexto.decir('Hola',[]);assert.equal(telefono,1);
contexto.rapida.checked=true;contexto.decir('Hola',[{}]);assert.equal(telefono,2);assert.equal(pc,1);
contexto.voz.checked=false;contexto.decir('Hola',[]);assert.equal(telefono,2);
console.log('OK: voz del PC, respaldo del telefono, preferencia y silencio.');

(async()=>{
  const codigo=html.slice(html.indexOf('function siguiente(){'),html.indexOf('function nivelVoz()'));
  let respaldo=0;const pausas=[];
  const s={voz:{checked:true},rapida:{checked:false},cola:[],textoRespaldoVoz:'',reprodujoVoz:false,
    estado:'esperando',poner(e){s.estado=e;},hablarTelefono(){respaldo++;},
    JarvisNativo:{enLlamada:()=>false,reproduciendoVoz(v){pausas.push(v);}},
    reproductor:{play:()=>Promise.reject(Error('no se puede reproducir'))}};
  vm.createContext(s);vm.runInContext(codigo,s);
  s.decir('Respuesta de respaldo',[{tipo:'audio/wav',datos:'invalido'}]);
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(respaldo,1);assert.deepEqual(pausas,[true,false]);
  console.log('OK: error de reproduccion recupera voz del telefono y libera escucha.');
})().catch(e=>{console.error(e);process.exitCode=1;});
