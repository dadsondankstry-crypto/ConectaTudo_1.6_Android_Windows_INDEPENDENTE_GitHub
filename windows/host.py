"""ConectaTudo LAN HTTP protocol v1 - Windows host, offline, no cloud."""
import json
import os
import re
import secrets
import socket
import ctypes
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlsplit
from io import BytesIO

SCREEN_RATE=9  # Limite de quadros/s: equilibra atraso, CPU e trafego

PORT = 8765
DISCOVERY_PORT = 39402
DISCOVERY_REQUEST = b'CT_DISCOVER_V1'
MAX_FILE = 20 * 1024**3
MAX_MESSAGE = 65536


def local_ips():
    found = set()
    try:
        import psutil
        for interface, addrs in psutil.net_if_addrs().items():
            for a in addrs:
                if a.family == socket.AF_INET and not a.address.startswith(('127.', '169.254.')):
                    found.add(a.address)
    except Exception:
        pass
    try:
        for a in socket.getaddrinfo(socket.gethostname(), None, family=socket.AF_INET):
            if a[4][0] != '127.0.0.1':
                found.add(a[4][0])
    except OSError:
        pass
    return sorted(found)


def safe_name(s):
    s = (s or 'arquivo').replace('\\', '/').split('/')[-1]
    s = ''.join(c for c in s if c >= ' ' and c not in '<>:"/\\|?*')
    return s[:160].strip(' .') or 'arquivo'


def unique_path(folder, name):
    base = safe_name(name)
    stem, ext = os.path.splitext(base)
    for idx in range(10000):
        label = base if idx == 0 else f'{stem} ({idx}){ext}'
        p = folder / label
        if not p.exists() and not (folder / ('.' + label + '.part')).exists():
            return p
    raise OSError('Muitos arquivos com o mesmo nome')


class DeviceHost:
    def __init__(self, folder=None, name=None):
        self.name = name or ('Windows • ' + socket.gethostname())
        self.pin = str(secrets.randbelow(900000) + 100000)
        self.folder = Path(folder or (Path.home() / 'Downloads' / 'ConectaTudo'))
        self.folder.mkdir(parents=True, exist_ok=True)
        self.messages = deque(maxlen=150)
        self.files = deque(maxlen=80)
        self.transfers = {}
        self.uploads = {}
        self.lock = threading.Lock()
        self.sharing_screen = False
        self.screen_mode = 'Equilibrado'
        # Remote keyboard / mouse: disabled until explicitly permitted in Windows UI.
        self.control_code = None
        self.control_until = 0.0
        self.control_failures = deque(maxlen=12)
        self.httpd = None
        self.discover_sock = None
        self.running = False
        self._frame_cv=threading.Condition()
        self._frame_data=None
        self._frame_id=0

    def start(self):
        if self.running:
            return
        self.httpd = ThreadingHTTPServer(('0.0.0.0', PORT), self._make_handler())
        self.httpd.daemon_threads = True
        self.running = True
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        threading.Thread(target=self._discover_loop, daemon=True).start()
        threading.Thread(target=self._capture_loop, daemon=True, name='ConectaTudo-tela').start()

    def stop(self):
        self.running = False
        self.sharing_screen = False
        with self._frame_cv:self._frame_cv.notify_all()
        self.revoke_control()
        if self.discover_sock:
            try: self.discover_sock.close()
            except OSError: pass
        if self.httpd:
            self.httpd.shutdown()
            self.httpd.server_close()
            self.httpd = None

    def authorize_control(self, minutes=15):
        with self.lock:
            self.control_code = ''.join(str(secrets.randbelow(10)) for _ in range(12))
            self.control_until = time.monotonic() + min(20,max(1,minutes))*60
            self.control_failures.clear()
            return self.control_code

    def revoke_control(self):
        with self.lock:
            self.control_code = None
            self.control_until = 0

    def control_active(self):
        with self.lock:
            return bool(self.control_code and self.control_until > time.monotonic() and self.sharing_screen)

    def check_control(self, code):
        now=time.monotonic()
        with self.lock:
            while self.control_failures and now-self.control_failures[0]>60:
                self.control_failures.popleft()
            if len(self.control_failures)>=8: return False
            if not (self.sharing_screen and self.control_code and self.control_until>now): return False
            good=secrets.compare_digest(code, self.control_code)
            if not good: self.control_failures.append(now)
            return good

    def _capture_jpeg(self, sct=None):
        from PIL import Image,ImageGrab
        if sct is not None:
            grabbed=sct.grab(sct.monitors[1])
            image=Image.frombytes('RGB',grabbed.size,grabbed.rgb)
        else:
            image=ImageGrab.grab(all_screens=False)
        profile = {'Econômico':((1100,760),58), 'Equilibrado':((1450,960),73), 'Nítido':((1920,1200),83), 'Baixa latência':((1150,760),64)}.get(self.screen_mode, ((1450,960),73))
        image.thumbnail(profile[0])
        b=BytesIO(); image.convert('RGB').save(b,format='JPEG',quality=profile[1],optimize=False)
        return b.getvalue()

    def _capture_loop(self):
        # Um capturador por Windows, independente da quantidade de visualizadores.
        # MSS acelera o Windows quando instalado; Pillow continua funcionando sozinho.
        sct=None
        try:
            from mss import mss
            sct=mss()
        except Exception:
            sct=None
        try:
            while self.running:
                if not self.sharing_screen:
                    time.sleep(.22)
                    continue
                started=time.monotonic()
                try:
                    frame=self._capture_jpeg(sct)
                    with self._frame_cv:
                        self._frame_data=frame
                        self._frame_id+=1
                        self._frame_cv.notify_all()
                except Exception:
                    # Falha do driver MSS? Recupera usando Pillow ImageGrab.
                    if sct is not None:
                        try:sct.close()
                        except Exception:pass
                        sct=None
                    time.sleep(.5)
                fps={'Econômico':6,'Equilibrado':9,'Nítido':6,'Baixa latência':12}.get(self.screen_mode,9)
                time.sleep(max(.018,1.0/fps-(time.monotonic()-started)))
        finally:
            if sct is not None:
                try:sct.close()
                except Exception:pass

    def frame_jpeg(self):
        with self._frame_cv:
            if self._frame_data is not None:return self._frame_data
        return self._capture_jpeg()

    def wait_frame(self,last,timeout=2.0):
        with self._frame_cv:
            if self._frame_id==last and self.running and self.sharing_screen:
                self._frame_cv.wait(timeout=timeout)
            return self._frame_id,self._frame_data

    def remote_input(self, data):
        # Windows only; called after both PIN and temporary control code verified.
        if os.name != 'nt': raise ValueError('Controle de mouse disponível somente no Windows')
        u = ctypes.windll.user32
        action=str(data.get('action',''))
        if action in ('click','double','right','move','wheel','swipe'):
            x=float(data.get('x',0.5));y=float(data.get('y',0.5))
            if not (0<=x<=1 and 0<=y<=1): raise ValueError('Coordenadas inválidas')
            sw,sh=u.GetSystemMetrics(0),u.GetSystemMetrics(1)
            if action!='wheel': u.SetCursorPos(int(x*(sw-1)),int(y*(sh-1)))
            if action in ('click','double','right'):
                count=2 if action=='double' else 1
                down,up=(0x0008,0x0010) if action=='right' else (0x0002,0x0004)
                for _ in range(count): u.mouse_event(down,0,0,0,0);u.mouse_event(up,0,0,0,0)
            elif action=='swipe':
                x2=float(data.get('x2',x));y2=float(data.get('y2',y))
                if not (0<=x2<=1 and 0<=y2<=1):raise ValueError('Destino invalido')
                duration=max(.15,min(1.2,float(data.get('duration',350))/1000))
                endx,endy=int(x2*(sw-1)),int(y2*(sh-1))
                u.mouse_event(0x0002,0,0,0,0)
                try:
                    steps=max(3,min(25,int(duration*30)))
                    for j in range(1,steps+1):
                        t=j/steps;u.SetCursorPos(int(x*(sw-1)*(1-t)+endx*t),int(y*(sh-1)*(1-t)+endy*t))
                        time.sleep(duration/steps)
                finally:u.mouse_event(0x0004,0,0,0,0)
            elif action=='wheel':
                delta=int(data.get('delta',0))
                if abs(delta)>5: raise ValueError('Roda inválida')
                u.mouse_event(0x0800,0,0,ctypes.c_ulong(delta*120).value,0)
        elif action=='key':
            keys={'enter':13,'backspace':8,'tab':9,'esc':27,'delete':46,
                  'up':38,'down':40,'left':37,'right':39,'home':36,'end':35}
            key=str(data.get('key','')).lower()
            if key not in keys: raise ValueError('Tecla não autorizada')
            u.keybd_event(keys[key],0,0,0);u.keybd_event(keys[key],0,0x0002,0)
        elif action=='text':
            # Unicode via SendInput; text is never executed as a shell command.
            value=str(data.get('text',''))
            if len(value)>300: raise ValueError('Máximo 300 caracteres')
            import ctypes.wintypes as wt
            U_PTR=ctypes.c_ulonglong if ctypes.sizeof(ctypes.c_void_p)==8 else ctypes.c_ulong
            class KEYBDINPUT(ctypes.Structure):
                _fields_=[('wVk',wt.WORD),('wScan',wt.WORD),('dwFlags',wt.DWORD),('time',wt.DWORD),('dwExtraInfo',U_PTR)]
            class MOUSEINPUT(ctypes.Structure):
                _fields_=[('dx',wt.LONG),('dy',wt.LONG),('mouseData',wt.DWORD),('dwFlags',wt.DWORD),('time',wt.DWORD),('dwExtraInfo',U_PTR)]
            class HARDWAREINPUT(ctypes.Structure):
                _fields_=[('uMsg',wt.DWORD),('wParamL',wt.WORD),('wParamH',wt.WORD)]
            class INPUTUNION(ctypes.Union):
                _fields_=[('mi',MOUSEINPUT),('ki',KEYBDINPUT),('hi',HARDWAREINPUT)]
            class INPUT(ctypes.Structure):
                _anonymous_=('u',)
                _fields_=[('type',wt.DWORD),('u',INPUTUNION)]
            raw=value.encode('utf-16-le')
            for idx in range(0,len(raw),2):
                code=int.from_bytes(raw[idx:idx+2],'little')
                for flags in (0x0004,0x0004|0x0002):
                    event=INPUT(1,KEYBDINPUT(0,code,flags,0,0))
                    if u.SendInput(1,ctypes.byref(event),ctypes.sizeof(INPUT))!=1:
                        raise OSError('Falha de entrada no Windows')
        else: raise ValueError('Ação inválida')

    def _discover_loop(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.discover_sock = s
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(('', DISCOVERY_PORT))
            s.settimeout(1.5)
            while self.running:
                try:
                    data, origin = s.recvfrom(1024)
                    if data != DISCOVERY_REQUEST:
                        continue
                    info = {'name': self.name, 'platform': 'windows', 'port': PORT}
                    s.sendto(json.dumps(info).encode(), origin)
                except socket.timeout:
                    pass
                except OSError:
                    if not self.running: break
        except OSError:
            pass
        finally:
            s.close()

    def _make_handler(self):
        host = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def log_message(self, fmt, *args):
                pass  # sem logs com PIN em texto

            def reply(self, status, body, typ='application/json; charset=utf-8', extra=None):
                if isinstance(body, str):
                    body = body.encode('utf-8')
                self.send_response(status)
                self.send_header('Content-Type', typ)
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Cache-Control', 'no-store')
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.send_header('Access-Control-Allow-Origin', 'null')
                if extra:
                    for k,v in extra.items(): self.send_header(k, v)
                self.end_headers()
                try: self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError): pass

            def json(self, status, data):
                self.reply(status, json.dumps(data, ensure_ascii=False).encode('utf-8'))

            def parse(self):
                u = urlsplit(self.path)
                q = parse_qs(u.query, keep_blank_values=True)
                return u.path, {k: v[0] for k,v in q.items()}

            def auth(self, q):
                return secrets.compare_digest(q.get('pin', ''), host.pin)

            def do_GET(self):
                path, q = self.parse()
                if path in ('/', '/screen', '/control', '/tablet'):
                    try:
                        body = (Path(__file__).resolve().parent / ('tablet.html' if path == '/tablet' else 'painel.html')).read_bytes()
                    except FileNotFoundError:
                        # PyInstaller onefile
                        import sys
                        body = (Path(getattr(sys, '_MEIPASS', '.')) / ('tablet.html' if path == '/tablet' else 'painel.html')).read_bytes()
                    self.reply(200, body, 'text/html; charset=utf-8')
                    return
                if not self.auth(q):
                    self.json(403, {'error': 'Código de acesso inválido'})
                    return
                if path == '/api/info':
                    self.json(200, {'name': host.name, 'platform': 'windows', 'version': '1.6', 'screen': host.sharing_screen, 'control': host.control_active()})
                elif path == '/api/messages':
                    with host.lock: data = list(host.messages)
                    self.json(200, {'messages': data})
                elif path == '/api/files':
                    with host.lock: data = list(host.files)
                    self.json(200, {'files': data})
                elif path == '/api/transfers':
                    with host.lock: data = list(host.transfers.values())[-20:] + [{'name': u['name'],'size':u['size'],'done':u['done'],'status':'receiving'} for u in host.uploads.values()]
                    self.json(200, {'transfers': data})
                elif path == '/api/download':
                    file = safe_name(q.get('name', ''))
                    # downloads limitados aos arquivos recebidos nesta sessão
                    with host.lock: known = any(x['name'] == file for x in host.files)
                    p = host.folder / file
                    if not known or not p.is_file():
                        self.json(404, {'error': 'Arquivo não encontrado'}); return
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/octet-stream')
                    self.send_header('Content-Length', str(p.stat().st_size))
                    self.send_header('Content-Disposition', "attachment; filename*=UTF-8''" + quote(file))
                    self.send_header('Cache-Control', 'no-store')
                    self.end_headers()
                    try:
                        with p.open('rb') as f:
                            while data := f.read(131072): self.wfile.write(data)
                    except (ConnectionError, OSError): pass
                elif path == '/api/screen.jpg':
                    if not host.sharing_screen:
                        self.json(409, {'error':'Tela não está sendo compartilhada'});return
                    try: self.reply(200,host.frame_jpeg(),'image/jpeg')
                    except Exception as e: self.json(500,{'error':'Falha na captura: '+str(e)[:100]})
                elif path == '/api/screen.mjpeg':
                    if not host.sharing_screen:
                        self.json(409, {'error':'Tela não compartilhada'});return
                    self.send_response(200)
                    self.send_header('Content-Type','multipart/x-mixed-replace; boundary=ctframe')
                    self.send_header('Cache-Control','no-store')
                    self.send_header('Connection','close')
                    self.end_headers()
                    try:
                        self.connection.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
                    except OSError:pass
                    try:
                        last_frame=0
                        while host.running and host.sharing_screen:
                            frame_id,frame=host.wait_frame(last_frame,2)
                            if frame is None or frame_id==last_frame:continue
                            last_frame=frame_id
                            self.wfile.write(b'--ctframe\r\nContent-Type: image/jpeg\r\nContent-Length: '+str(len(frame)).encode()+b'\r\n\r\n')
                            self.wfile.write(frame+b'\r\n')
                            self.wfile.flush()
                    except (OSError,ConnectionError,BrokenPipeError): pass
                    except Exception: pass
                    self.close_connection=True
                elif path == '/api/upload/status':
                    with host.lock: up=host.uploads.get(q.get('id',''))
                    if up: self.json(200,{'id':q.get('id'),'offset':up['done'],'size':up['size']})
                    else: self.json(404,{'error':'Envio não encontrado nesta sessão'})
                elif path == '/api/control/status':
                    self.json(200,{'enabled':host.control_active()})
                else:
                    self.json(404, {'error': 'Rota desconhecida'})

            def do_POST(self):
                path, q = self.parse()
                if not self.auth(q):
                    self.json(403, {'error': 'PIN inválido'}); return
                try: n = int(self.headers.get('Content-Length', '-1'))
                except ValueError: n = -1
                if n < 0:
                    self.json(411, {'error': 'Content-Length necessário'}); return
                if path == '/api/message':
                    if n > MAX_MESSAGE:
                        self.json(413, {'error': 'Texto muito grande'}); return
                    text = self.rfile.read(n).decode('utf-8', errors='replace').strip()
                    if not text:
                        self.json(400, {'error': 'Texto vazio'}); return
                    with host.lock:
                        host.messages.append({'text': text, 'who': 'Outro dispositivo', 'time': int(time.time() * 1000)})
                    self.json(200, {'ok': True})
                elif path == '/api/upload/init':
                    if n>4096: self.json(413,{'error':'Dados grandes'});return
                    try:
                        data=json.loads(self.rfile.read(n))
                        identifier=str(data.get('id',''))
                        name=safe_name(data.get('name',''))
                        size=int(data.get('size',-1))
                        if not re.fullmatch(r'[a-zA-Z0-9_-]{10,64}',identifier) or not(0<=size<=MAX_FILE):
                            raise ValueError('Identificador/tamanho inválido')
                        with host.lock:
                            up=host.uploads.get(identifier)
                            if up:
                                if up['name']!=name or up['size']!=size:raise ValueError('ID já usado por outro arquivo')
                            else:
                                dst=unique_path(host.folder,name)
                                temp=dst.with_name('.'+dst.name+'.'+identifier+'.part')
                                temp.touch(exist_ok=False)
                                up={'name':name,'size':size,'done':0,'temp':temp,'dst':dst,'time':time.monotonic()}
                                host.uploads[identifier]=up
                            self.json(200,{'id':identifier,'offset':up['done'],'size':size})
                    except (ValueError,TypeError,OSError,KeyError) as e:
                        self.json(400,{'error':str(e)[:180]})
                elif path == '/api/upload/chunk':
                    identifier=q.get('id','')
                    try: offset=int(q.get('offset','-1'))
                    except ValueError: offset=-1
                    if n>1024*1024 or n<=0:
                        self.json(413,{'error':'Partes de até 1 MB'});return
                    with host.lock:
                        up=host.uploads.get(identifier)
                        if not up:
                            self.json(404,{'error':'Envio não iniciado'});return
                        if offset!=up['done'] or offset+n>up['size']:
                            self.json(409,{'error':'Posição divergente','offset':up['done']});return
                        try:
                            data=self.rfile.read(n)
                            if len(data)!=n:raise IOError('Parte incompleta')
                            with up['temp'].open('ab') as stream: stream.write(data)
                            up['done']+=n
                            self.json(200,{'offset':up['done']})
                        except OSError as e: self.json(500,{'error':str(e)[:120]})
                elif path == '/api/upload/finish':
                    identifier=q.get('id','')
                    with host.lock:
                        up=host.uploads.get(identifier)
                        if not up: self.json(404,{'error':'Envio não encontrado'});return
                        if up['done']!=up['size']:
                            self.json(409,{'error':'Ainda faltam dados','offset':up['done']});return
                        try:
                            os.replace(up['temp'],up['dst'])
                            host.files.appendleft({'name':up['dst'].name,'size':up['size'],'time':int(time.time()*1000)})
                            host.uploads.pop(identifier,None)
                            self.json(200,{'ok':True,'name':up['dst'].name})
                        except OSError as e:self.json(500,{'error':str(e)[:160]})
                elif path == '/api/upload/cancel':
                    identifier=q.get('id','')
                    with host.lock:
                        up=host.uploads.pop(identifier,None)
                        if up:
                            try: up['temp'].unlink(missing_ok=True)
                            except OSError: pass
                    self.json(200,{'ok':True})
                elif path == '/api/control/input':
                    if n>2048:
                        self.json(413,{'error':'Comando muito grande'});return
                    try:
                        data=json.loads(self.rfile.read(n).decode('utf-8'))
                        if not host.check_control(self.headers.get('X-CT-Control','')):
                            self.json(403,{'error':'Controle bloqueado. Ative no Windows e informe o código temporário.'});return
                        host.remote_input(data)
                        self.json(200,{'ok':True})
                    except (ValueError,TypeError) as e:
                        self.json(400,{'error':str(e)[:140]})
                    except Exception as e:
                        self.json(500,{'error':'Falha no controle: '+str(e)[:110]})
                elif path == '/api/send':
                    if n > MAX_FILE:
                        self.json(413, {'error': 'Arquivo maior que 20 GB'}); return
                    filename = safe_name(q.get('name', 'arquivo'))
                    transfer_id = str(time.time_ns())
                    temp = None
                    try:
                        with host.lock:
                            dst = unique_path(host.folder, filename)
                            host.transfers[transfer_id] = {'name': filename, 'size': n, 'done': 0, 'status': 'receiving'}
                            if len(host.transfers) > 30:
                                for old in list(host.transfers)[:10]: host.transfers.pop(old, None)
                        temp = dst.with_name('.' + dst.name + '.part')
                        left = n
                        last_update = 0.0
                        with temp.open('xb') as f:
                            while left > 0:
                                chunk = self.rfile.read(min(128 * 1024, left))
                                if not chunk: raise IOError('Conexão interrompida')
                                f.write(chunk); left -= len(chunk)
                                now = time.monotonic()
                                if now - last_update > .14 or left == 0:
                                    with host.lock:
                                        host.transfers[transfer_id]['done'] = n - left
                                    last_update = now
                        os.replace(temp, dst)
                        with host.lock:
                            host.files.appendleft({'name': dst.name, 'size': n, 'time': int(time.time() * 1000)})
                            host.transfers[transfer_id].update(done=n, status='complete')
                        self.json(200, {'ok': True, 'name': dst.name})
                    except Exception as e:
                        try:
                            if temp is not None: temp.unlink(missing_ok=True)
                        except Exception: pass
                        with host.lock:
                            host.transfers[transfer_id]['status'] = 'error'
                        self.json(500, {'error': str(e)[:150]})
                else:
                    self.json(404, {'error': 'Rota desconhecida'})

        return Handler


def discover(timeout=3.0):
    results = []
    seen = set()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.35)
        targets = ['255.255.255.255']
        try:
            import psutil
            for addrs in psutil.net_if_addrs().values():
                for a in addrs:
                    if a.family == socket.AF_INET and a.broadcast:
                        targets.append(a.broadcast)
        except Exception:
            pass
        for ip in set(targets):
            try: s.sendto(DISCOVERY_REQUEST, (ip, DISCOVERY_PORT))
            except OSError: pass
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                data, addr = s.recvfrom(2048)
                info = json.loads(data)
                if 'name' in info and addr[0] not in seen:
                    seen.add(addr[0]); info['ip'] = addr[0]; results.append(info)
            except (socket.timeout, ValueError, UnicodeDecodeError):
                pass
    finally:
        s.close()
    return results
