declare const Deno: { env: { get(name:string):string|undefined }; serve(handler:(request:Request)=>Promise<Response>):void }
declare const EdgeRuntime: { waitUntil(task:Promise<unknown>):void }
