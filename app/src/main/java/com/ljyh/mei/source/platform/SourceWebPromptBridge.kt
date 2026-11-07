package com.ljyh.mei.source.platform

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.webkit.JsPromptResult
import android.webkit.WebView
import android.widget.Toast
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Fixed prompt verbs only. No Android object or source-supplied JavaScript is installed. */
internal class SourceWebPromptBridge(
    private val context: Context,
    private val page: SourceWebRequest,
    private val transport: SourceWebTransport,
    private val scope: CoroutineScope,
    private val dark: Boolean,
    private val setTitle: (String) -> Unit,
    private val open: suspend (String, Boolean) -> Boolean,
    private val closePage: () -> Unit,
    private val hasRecentGesture: () -> Boolean,
) : AutoCloseable {
    private val prompts = ConcurrentHashMap.newKeySet<JsPromptResult>()
    private var lastToast = 0L

    fun onPrompt(view: WebView, url: String?, message: String?, result: JsPromptResult): Boolean {
        val origin = runCatching { page.recipe.checkedPageUrl(url ?: denied()) }.getOrNull()
        val request = message?.takeIf { it.length <= 400_000 && boundedJson(it) }
            ?.let { runCatching { JsonParser.parseString(it).takeIf { value -> value.isJsonObject }?.asJsonObject }.getOrNull() }
        if (origin == null || request == null || !page.isActive) { result.cancel(); return true }
        if (request.get("\$sourceTransport")?.takeIf { it.isJsonPrimitive }?.asString == page.scope.token) {
            val parameters = request.get("request")?.takeIf { it.isJsonObject }?.asJsonObject
            if (parameters == null) { result.cancel(); return true }
            if (parameters.has("prepareFileChooser")) {
                if (!hasRecentGesture()) { result.cancel(); return true }
                transport.prepareFileChooser(origin)
                result.confirm("{\"ok\":true}")
                return true
            }
            prompts.add(result)
            // Prompt waits in the renderer, while the host UI and the network coroutine remain free.
            // Replying to this prompt also works for a cross-origin frame without leaking a reply to other frames.
            scope.launch {
                try {
                    val value = transport.fetch(parameters, origin)
                    page.scope.ensureCurrent()
                    result.confirm(JsonObject().apply { addProperty("ok", true); add("response", value) }.toString())
                } catch (cancelled: CancellationException) {
                    result.cancel()
                    throw cancelled
                } catch (_: Exception) { result.confirm("{\"ok\":false}") }
                finally { prompts.remove(result) }
            }
            return true
        }
        if (message.orEmpty().length > 65_536 || !request.has("seq") || !request.has("method")) { result.cancel(); return true }
        val sequence = request.get("seq")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asBigDecimal.intValueExact() }.getOrNull() }?.takeIf { it >= 0 }
        val remote = request.get("method")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (sequence == null || remote == null) { result.cancel(); return true }
        val verb = page.recipe.bridgeAliases[remote] ?: remote.takeIf { it in SourceWebRecipe.verbs }
        val parameters = request.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        result.confirm("")
        scope.launch {
            var error: JsonObject? = null
            val response = try {
                page.scope.ensureCurrent()
                if (verb == null) throw UnknownVerb()
                dispatch(view, verb, parameters)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: UnknownVerb) {
                error = JsonObject().apply { addProperty("code", 404); addProperty("message", "Method not found") }
                JsonNull.INSTANCE
            } catch (_: Exception) {
                error = JsonObject().apply { addProperty("code", 403); addProperty("message", "Request denied") }
                JsonNull.INSTANCE
            }
            if (page.isActive && view.url == url) {
                // Both function name and script structure are host constants. Values are JSON encoded.
                view.evaluateJavascript("window.MNBCallback&&window.MNBCallback($sequence,${error ?: "null"},$response);", null)
            }
        }
        return true
    }

    private suspend fun dispatch(view: WebView, verb: String, p: JsonObject): com.google.gson.JsonElement {
        val density = context.resources.displayMetrics.density
        return when (verb) {
            "device.info" -> JsonObject().apply {
                addProperty("deviceName", Build.MODEL); addProperty("os", "android"); addProperty("osVersion", Build.VERSION.RELEASE)
                addProperty("sdkNamespace", page.owner.sdkNamespace)
                addProperty("screenWidth", view.width / density); addProperty("screenHeight", view.height / density)
                addProperty("statusBarHeight", 0); addProperty("navigationBarHeight", 0)
            }
            "page.info" -> JsonObject().apply {
                addProperty("code", 200); addProperty("width", view.width / density); addProperty("height", view.height / density)
                addProperty("statusBarHeight", 0); addProperty("navigationBarHeight", 0)
                addProperty("theme", if (dark) "dark" else "light"); addProperty("url", view.url)
            }
            "page.title", "page.ui" -> JsonObject().also { p.optionalString("title", 200)?.trim()?.takeIf(String::isNotEmpty)?.let(setTitle) }
            "navigation.open" -> JsonObject().also { if (!open(p.string("url"), hasRecentGesture())) denied() }
            "navigation.close" -> JsonObject().also { closePage() }
            "session.present" -> JsonObject().apply { addProperty("isLogin", page.owner.account != null) }
            "session.profile" -> {
                val account = page.owner.account
                JsonObject().apply {
                    addProperty("userId", account?.ref?.accountId?.value ?: "0")
                    addProperty("nickname", account?.displayName.orEmpty()); addProperty("avatarUrl", account?.avatarUrl.orEmpty())
                    add("profile", JsonObject().apply {
                        addProperty("userId", account?.ref?.accountId?.value ?: "0")
                        addProperty("nickname", account?.displayName.orEmpty()); addProperty("avatarUrl", account?.avatarUrl.orEmpty())
                    })
                }
            }
            "session.login" -> JsonObject().also { if (!hasRecentGesture() || !page.owner.login()) denied() }
            "toast.show" -> JsonObject().also {
                val text = p.optionalString("message", 300) ?: p.optionalString("text", 300).orEmpty()
                val now = SystemClock.elapsedRealtime()
                if (text.isNotBlank() && now - lastToast > 1000) { lastToast = now; Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
            }
            else -> throw UnknownVerb()
        }
    }

    override fun close() { prompts.forEach(JsPromptResult::cancel); prompts.clear() }
    private class UnknownVerb : Exception()

    private fun boundedJson(value: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in value) {
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '[', '{' -> if (++depth > 20) return false
                ']', '}' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !quoted
    }
}

/** Installed before any document script, in every frame. This is host code, never a plugin recipe. */
internal fun sourceWebNetworkGuard(token: String): String = """
(function(){
 'use strict';
 const nativePrompt=window.prompt.bind(window), networkToken=${com.google.gson.Gson().toJson(token)};
 const bytesToBase64=bytes=>{let value='';for(let i=0;i<bytes.length;i+=8192)value+=String.fromCharCode.apply(null,bytes.subarray(i,i+8192));return btoa(value);};
 const MAX_BODY=262144, nativeBlobSlice=Blob.prototype.slice, slicedBlobs=new WeakMap(), nativeRequest=window.Request, requestBodies=new WeakMap();
 const callNative=request=>JSON.parse(nativePrompt(JSON.stringify({${'$'}sourceTransport:networkToken,request}))||'{}');
 const boundBytes=bytes=>{if(bytes.byteLength>MAX_BODY)throw new TypeError('Request too large');return bytesToBase64(bytes);};
 const fileBody=blob=>{
   const sliced=slicedBlobs.get(blob),source=sliced?sliced.source:blob;
   if(!(source instanceof File))return null;
   const answer=callNative({resolveFile:{name:source.name,size:source.size}});
   if(!answer.ok)return null;
   return {handle:answer.response.handle,offset:sliced?sliced.offset:0,length:blob.size,mimeType:blob.type||source.type||'application/octet-stream'};
 };
 const requestBody=async body=>{
   if(body==null)return {};
   if(body instanceof FormData){
     const multipart=[];let total=0;
     for(const [name,value]of body){
       if(multipart.length>=128||name.length>2048)throw new TypeError('Request too large');
       if(typeof value==='string'){if(value.length>MAX_BODY)throw new TypeError('Request too large');total+=new TextEncoder().encode(value).length;multipart.push({name,value});}
       else {const file=fileBody(value),filename=value.name||'blob',mimeType=value.type||'application/octet-stream';if(file)multipart.push({name,filename,mimeType,file});else{if(value.size>MAX_BODY)throw new TypeError('Unselected file');total+=value.size;multipart.push({name,filename,mimeType,bytes:boundBytes(new Uint8Array(await value.arrayBuffer()))});}}
       if(total>MAX_BODY)throw new TypeError('Request too large');
     }
     return {multipart};
   }
   if(body instanceof Blob){const file=fileBody(body);if(file)return {file};if(body.size>MAX_BODY)throw new TypeError('Request too large');return {body:boundBytes(new Uint8Array(await body.arrayBuffer()))};}
   if(typeof body==='string'){if(body.length>MAX_BODY)throw new TypeError('Request too large');return {body:boundBytes(new TextEncoder().encode(body))};}
   if(body instanceof URLSearchParams)return requestBody(body.toString());
   if(body instanceof ArrayBuffer)return {body:boundBytes(new Uint8Array(body))};
   if(ArrayBuffer.isView(body))return {body:boundBytes(new Uint8Array(body.buffer,body.byteOffset,body.byteLength))};
   throw new TypeError('Unsupported request body');
 };
 const boundedRequestBody=async request=>{
   if(requestBodies.has(request))return requestBodies.get(request);
   if(!request.body)return null;
   const reader=request.body.getReader(),chunks=[];let size=0;
   try{while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>MAX_BODY)throw new TypeError('Request too large');chunks.push(value);}}finally{await reader.cancel().catch(()=>{});}
   const joined=new Uint8Array(size);let offset=0;for(const chunk of chunks){joined.set(chunk,offset);offset+=chunk.byteLength;}return joined;
 };
 class SafeRequest extends nativeRequest {
   constructor(input,options={}){super(input,options);const value=options.body!==undefined?options.body:requestBodies.get(input);if(value!==undefined)requestBodies.set(this,value);}
   clone(){const result=super.clone();if(requestBodies.has(this))requestBodies.set(result,requestBodies.get(this));return result;}
 }
 Blob.prototype.slice=function(start=0,end=this.size,type=''){
   const result=nativeBlobSlice.call(this,start,end,type),base=slicedBlobs.get(this)||{source:this,offset:0};
   const number=Math.trunc(Number(start)||0),from=number<0?Math.max(this.size+number,0):Math.min(number,this.size);
   slicedBlobs.set(result,{source:base.source,offset:base.offset+from});return result;
 };
 const nativeAppend=FormData.prototype.append,nativeSet=FormData.prototype.set,nativeGetAll=FormData.prototype.getAll;
 const rememberPart=(form,name,value)=>{if(value instanceof Blob){const parts=nativeGetAll.call(form,String(name)),stored=parts[parts.length-1];if(stored instanceof Blob)slicedBlobs.set(stored,slicedBlobs.get(value)||{source:value,offset:0});}};
 FormData.prototype.append=function(name,value,...rest){nativeAppend.call(this,name,value,...rest);rememberPart(this,name,value);};
 FormData.prototype.set=function(name,value,...rest){nativeSet.call(this,name,value,...rest);rememberPart(this,name,value);};
 const safeFetch=async(input,options={})=>{
   const request=input instanceof nativeRequest?input:null;
   const url=new URL(request?request.url:String(input),location.href).href;
   const method=String(options.method||(request&&request.method)||'GET').toUpperCase();
   const headers=Object.fromEntries(new Headers(options.headers||(request&&request.headers)||{}));
   const raw=options.body!==undefined?options.body:(request&&!['GET','HEAD'].includes(method)?await boundedRequestBody(request):null);
   if(!Object.keys(headers).some(key=>key.toLowerCase()==='content-type')){
     if(raw instanceof URLSearchParams)headers['Content-Type']='application/x-www-form-urlencoded;charset=UTF-8';
     else if(raw instanceof Blob&&raw.type)headers['Content-Type']=raw.type;
     else if(typeof raw==='string')headers['Content-Type']='text/plain;charset=UTF-8';
   }
   if(raw instanceof FormData)for(const key of Object.keys(headers))if(key.toLowerCase()==='content-type')delete headers[key];
   const descriptor=await requestBody(raw);
   if(options.signal&&options.signal.aborted)throw new DOMException('Aborted','AbortError');
   const answer=callNative({url,method,headers,...descriptor,credentials:options.credentials||(request&&request.credentials)||'same-origin'});
   if(!answer.ok)throw new TypeError('Network request denied');
   const reply=answer.response, decoded=atob(reply.body), bytes=new Uint8Array(decoded.length);
   for(let i=0;i<decoded.length;i++)bytes[i]=decoded.charCodeAt(i);
   const response=new Response(['HEAD'].includes(method)||[204,205,304].includes(reply.status)?null:bytes,{status:reply.status,statusText:reply.statusText,headers:reply.headers});
   Object.defineProperty(response,'url',{value:reply.url});
   return response;
 };
 class SafeXHR extends EventTarget {
   constructor(){super();this.readyState=0;this.status=0;this.statusText='';this.response=null;this.responseText='';this.responseType='';this.timeout=0;this.withCredentials=false;this.upload=new EventTarget();this._headers={};this._aborted=false;}
   _event(name){const event=new Event(name);this.dispatchEvent(event);const listener=this['on'+name];if(typeof listener==='function')listener.call(this,event);}
   open(method,url,async=true){if(async===false)throw new TypeError('Synchronous XHR is unavailable');this._method=String(method).toUpperCase();this._url=new URL(url,location.href).href;this.readyState=1;this._event('readystatechange');}
   setRequestHeader(name,value){this._headers[String(name)]=String(value);}
   getResponseHeader(name){return this._responseHeaders?this._responseHeaders.get(name):null;}
   getAllResponseHeaders(){return this._responseHeaders?[...this._responseHeaders].map(([k,v])=>k+': '+v+'\r\n').join(''):'';}
   overrideMimeType(){}
   abort(){this._aborted=true;this.readyState=0;this._event('abort');this._event('loadend');}
   async send(body=null){this._event('loadstart');try{const response=await safeFetch(this._url,{method:this._method,headers:this._headers,body,credentials:this.withCredentials?'include':'same-origin'});if(this._aborted)return;this.status=response.status;this.statusText=response.statusText;this.responseURL=response.url;this._responseHeaders=response.headers;this.readyState=2;this._event('readystatechange');const bytes=await response.arrayBuffer();this.readyState=3;this._event('readystatechange');if(this.responseType==='arraybuffer')this.response=bytes;else if(this.responseType==='blob')this.response=new Blob([bytes],{type:response.headers.get('Content-Type')||''});else{const text=new TextDecoder().decode(bytes);if(this.responseType==='json')this.response=JSON.parse(text);else if(this.responseType==='document')this.response=new DOMParser().parseFromString(text,'text/html');else this.response=this.responseText=text;}this.readyState=4;this._event('readystatechange');this._event('load');this._event('loadend');}catch(error){if(this._aborted)return;this.readyState=4;this._event('readystatechange');this._event('error');this._event('loadend');}}
 }
 const lock=(object,name,value)=>{try{Object.defineProperty(object,name,{value,writable:false,configurable:false});}catch(_){}};
 const unavailable=function(){throw new TypeError('Unavailable network capability');};
 lock(window,'fetch',safeFetch);lock(window,'XMLHttpRequest',SafeXHR);lock(window,'Request',SafeRequest);
 for(const name of ['WebSocket','EventSource','Worker','SharedWorker','RTCPeerConnection','webkitRTCPeerConnection','WebTransport'])lock(window,name,unavailable);
 lock(navigator,'sendBeacon',()=>false);
 try{lock(navigator,'serviceWorker',undefined);}catch(_){}
 document.addEventListener('click',event=>{if(event.target instanceof HTMLInputElement&&event.target.type==='file')callNative({prepareFileChooser:true});},true);
 const submit=async form=>{
   const method=(form.method||'GET').toUpperCase(),url=new URL(form.action||location.href,location.href),fields=new FormData(form),data=new URLSearchParams();
   for(const [key,value]of fields)data.append(key,typeof value==='string'?value:value.name);
   if(method==='GET'){for(const [key,value]of data)url.searchParams.append(key,value);location.href=url.href;return;}
   const body=String(form.enctype).toLowerCase()==='multipart/form-data'?fields:data;
   const response=await safeFetch(url.href,{method,body,credentials:'include'});
   if(new URL(response.url).origin!==location.origin)throw new TypeError('Cross-origin form navigation is unavailable');
   history.replaceState(null,'',response.url);
   const text=await response.text();
   document.open();document.write(text);document.close();
 };
 document.addEventListener('submit',event=>{if(event.target instanceof HTMLFormElement){event.preventDefault();submit(event.target).catch(()=>{});}},true);
 lock(HTMLFormElement.prototype,'submit',function(){submit(this).catch(()=>{});});
})();
""".trimIndent()
