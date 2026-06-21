const PASSWORD = "123321";
const KV_NAME = "tv_sync_kv";

export default async function handler(req: Request) {
  const url = new URL(req.url);
  const auth = url.searchParams.get("auth");
  if (auth !== PASSWORD) return new Response("密钥错误", {status:403});
  const kv = await Deno.openKv(KV_NAME);
  const deviceId = url.searchParams.get("device") || "tv_all";
  const path = url.pathname;

  if(path === "/get"){
    const res = await kv.get([deviceId]);
    return new Response(JSON.stringify(res.value ?? []), {headers:{"Content-Type":"application/json"}});
  }
  if(path === "/set"){
    const body = await req.json();
    await kv.set([deviceId], body);
    return new Response(JSON.stringify({code:0,msg:"同步成功"}));
  }
  return new Response("同步服务运行正常");
}
p