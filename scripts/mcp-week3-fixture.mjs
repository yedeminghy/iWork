import http from 'node:http';
import readline from 'node:readline';
import { appendFileSync, existsSync, unlinkSync } from 'node:fs';

const mode=process.argv[2];
const cliDangerMarker=mode==='stdio'?process.argv[3]:mode==='stdio-exit-once'?process.argv[4]:undefined;
const token=process.env.AGENT_W3_FIXTURE_TOKEN;
let httpFailureBudget=Number(process.env.AGENT_W3_HTTP_FAIL_FIRST??0);
const httpDropFirst=process.env.AGENT_W3_HTTP_DROP_FIRST==='1';
let dropped=false;
function response(message){
  if(message.method==='initialize')return {jsonrpc:'2.0',id:message.id,result:{protocolVersion:'2025-11-25',capabilities:{tools:{listChanged:false}},serverInfo:{name:'agent-desktop-week3-fixture',version:'1.0.0'}}};
  if(message.method==='tools/list')return {jsonrpc:'2.0',id:message.id,result:{tools:[{name:'echo',description:'Returns the provided text',inputSchema:{type:'object',properties:{text:{type:'string'}},required:['text'],additionalProperties:false}},{name:'sum',description:'Adds two numbers',inputSchema:{type:'object',properties:{a:{type:'number'},b:{type:'number'}},required:['a','b'],additionalProperties:false}},{name:'danger',description:'Unknown-risk test tool',inputSchema:{type:'object',properties:{},additionalProperties:false}}]}};
  if(message.method==='tools/call'){
    const name=message.params?.name;const args=message.params?.arguments??{};
    if(name==='echo'){const value=String(args.text??'');if(value==='__exit__')return {exit:true};return {delay:value==='__delay__'?5000:0,message:{jsonrpc:'2.0',id:message.id,result:{content:[{type:'text',text:value}],structuredContent:{text:value},isError:false}}};}
    if(name==='sum'){const value=Number(args.a)+Number(args.b);return {jsonrpc:'2.0',id:message.id,result:{content:[{type:'text',text:String(value)}],structuredContent:{value},isError:false}};}
    if(name==='danger'){appendFileSync(process.env.AGENT_W6_DANGER_MARKER??cliDangerMarker??'danger-marker.txt','executed\n','utf8');return {jsonrpc:'2.0',id:message.id,result:{content:[{type:'text',text:'danger fixture executed'}],structuredContent:{executed:true},isError:false}};}
  }
  if(message.method?.startsWith('notifications/'))return null;
  return {jsonrpc:'2.0',id:message.id,error:{code:-32601,message:'Method not found'}};
}

if(mode==='stdio'||mode==='stdio-exit-once'){
  const lines=readline.createInterface({input:process.stdin,crlfDelay:Infinity});
  const exitMarker=process.argv[3];
  lines.on('line',line=>{try{const request=JSON.parse(line);const reply=response(request);if(!reply)return;if(reply.exit){process.stderr.write('fixture requested exit; token=[REDACTED]\n');process.exit(23);return;}const delayed=reply.delay??0;const message=reply.message??reply;setTimeout(()=>{
    process.stdout.write(JSON.stringify(message)+'\n');
    if(mode==='stdio-exit-once'&&request.method==='tools/list'&&exitMarker&&existsSync(exitMarker)){
      unlinkSync(exitMarker);
      setTimeout(()=>{process.stderr.write('fixture exited after catalog; token=[REDACTED]\n');process.exit(23);},250);
    }
  },delayed);}catch{process.stderr.write('fixture protocol error\n');}});
}else if(mode==='http'){
  const server=http.createServer((request,res)=>{
    if(request.method!=='POST'){res.writeHead(405).end();return;}
    if(process.env.AGENT_W3_HTTP_REQUEST_MARKER)appendFileSync(process.env.AGENT_W3_HTTP_REQUEST_MARKER,'request\n','utf8');
    if(token&&request.headers.authorization!==`Bearer ${token}`){res.writeHead(401,{'Content-Type':'application/json'}).end(JSON.stringify({error:'unauthorized'}));return;}
    if(httpFailureBudget>0){httpFailureBudget-=1;res.writeHead(503,{'Content-Type':'application/json'}).end(JSON.stringify({error:'temporary'}));return;}
    if(httpDropFirst&&!dropped){dropped=true;request.socket.destroy();return;}
    let body='';request.setEncoding('utf8');request.on('data',chunk=>body+=chunk);request.on('end',()=>{try{const reply=response(JSON.parse(body));if(!reply){res.writeHead(202).end();return;}const delayed=reply.delay??0;const message=reply.message??reply;setTimeout(()=>{res.writeHead(200,{'Content-Type':'application/json','Mcp-Session-Id':'week3-fixture'});res.end(JSON.stringify(message));},delayed);}catch{res.writeHead(400).end();}});
  });
  server.listen(0,'127.0.0.1',()=>process.stdout.write(JSON.stringify({port:server.address().port})+'\n'));
}else throw new Error('Expected stdio, stdio-exit-once or http mode');
