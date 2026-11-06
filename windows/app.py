"""Interface portátil Windows para ConectaTudo. Sem conta e sem internet externa."""
import http.client
import json
import os
import queue
import socket
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from urllib.parse import quote
import urllib.request
import webbrowser
import zipfile
import tempfile
from host import DeviceHost, PORT, discover, local_ips

class App:
    def __init__(self, root):
        self.root = root
        root.title('ConectaTudo 1.6 • Windows')
        root.geometry('780x850')
        root.minsize(630, 650)
        self.host = DeviceHost()
        self.messages = queue.Queue()
        self.peer_ip = tk.StringVar()
        self.peer_pin = tk.StringVar()
        self.send_text = tk.StringVar()
        self.screen = tk.BooleanVar(value=False)
        self.screen_mode = tk.StringVar(value='Equilibrado')
        self.control_status = tk.StringVar(value='🔒 Controle remoto DESATIVADO')
        self.status = tk.StringVar(value='Preparando servidor...')
        self.remote_name = tk.StringVar(value='Não conectado')
        self.progress_label = tk.StringVar(value='📦 Nenhuma transferência ativa')
        self.incoming_label = tk.StringVar(value='')
        self._last_local = 0.0
        self._seen_messages = set()
        self._seen_files = set()
        self._busy = False
        self._ui()
        try:
            self.host.start()
            self.refresh_status()
        except OSError as e:
            self.status.set('Não foi possível abrir a porta 8765: ' + str(e))
            messagebox.showerror('ConectaTudo', 'Porta 8765 ocupada. Feche outra cópia do programa.\n' + str(e))
        root.after(100, self._pump)
        root.protocol('WM_DELETE_WINDOW', self.close)

    def _ui(self):
        bg = '#121a2b'
        self.root.configure(bg=bg)
        style = ttk.Style()
        style.theme_use('clam')
        style.configure('TFrame', background=bg)
        style.configure('Card.TFrame', background='#202d46')
        style.configure('TSeparator', background='#374b68')
        style.configure('TCheckbutton', background=bg, foreground='#dce9ff', font=('Segoe UI', 10))
        style.map('TCheckbutton', background=[('active',bg)], foreground=[('active','#ffffff')])
        style.configure('TLabel', background=bg, foreground='#e8efff', font=('Segoe UI', 10))
        style.configure('Header.TLabel', background=bg, foreground='#6fb0ff', font=('Segoe UI', 17, 'bold'))
        style.configure('TButton', font=('Segoe UI', 10, 'bold'), padding=(14,11),
                        background='#263956', foreground='#f1f6ff', borderwidth=0, relief='flat')
        style.map('TButton', background=[('pressed','#1054a1'),('active','#395e91')],
                  foreground=[('disabled','#9caec5'),('active','#ffffff')])
        style.configure('Primary.TButton', font=('Segoe UI', 10, 'bold'), padding=(16,12),
                        background='#347cf1', foreground='white', borderwidth=0)
        style.map('Primary.TButton', background=[('pressed','#164b9c'),('active','#5697ff')], foreground=[('active','white')])
        style.configure('Success.TButton', font=('Segoe UI', 10, 'bold'), padding=(16,12),
                        background='#13765f', foreground='white', borderwidth=0)
        style.map('Success.TButton', background=[('pressed','#0a5343'),('active','#218c72')])
        style.configure('Muted.TLabel', background=bg, foreground='#a5bad5', font=('Segoe UI', 10))
        style.configure('Section.TLabel', background=bg, foreground='#94c5ff', font=('Segoe UI', 12, 'bold'))
        style.configure('TProgressbar', background='#3be0b4', troughcolor='#263752', borderwidth=0, thickness=15)
        style.configure('TEntry', padding=9, fieldbackground='#eaf2ff', foreground='#0e1930')
        # Região rolável para caber na tela de 800p do Legion Go com escala de interface.
        container = ttk.Frame(self.root)
        container.pack(fill='both', expand=True)
        canvas = tk.Canvas(container, bg=bg, highlightthickness=0, bd=0)
        scrollbar = ttk.Scrollbar(container, orient='vertical', command=canvas.yview)
        canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right', fill='y')
        canvas.pack(side='left', fill='both', expand=True)
        outer = ttk.Frame(canvas, padding=18)
        window_id = canvas.create_window((0,0), window=outer, anchor='nw')
        outer.bind('<Configure>', lambda _e: canvas.configure(scrollregion=canvas.bbox('all')))
        canvas.bind('<Configure>', lambda e: canvas.itemconfigure(window_id, width=e.width))
        def mousewheel(event):
            canvas.yview_scroll(-1 if event.delta > 0 else 1, 'units')
        canvas.bind_all('<MouseWheel>', mousewheel)
        ttk.Label(outer, text='🔗 ConectaTudo 1.6 • Windows', style='Header.TLabel').pack(anchor='w')
        ttk.Label(outer, text='Transferência, conversa, tela e controle • Wi-Fi local ou VPN privada', style='Muted.TLabel').pack(anchor='w', pady=(3, 10))
        tip = ttk.Frame(outer)
        tip.pack(fill='x', pady=(0,12))
        ttk.Label(tip, text='🟢 SERVIDOR LOCAL', foreground='#48dda9', font=('Segoe UI',10,'bold')).pack(side='left')
        ttk.Label(tip, text='  •  Dados pela rede local ou sua VPN privada', style='Muted.TLabel').pack(side='left')
        ttk.Label(outer, text='💻  ESTE COMPUTADOR', style='Section.TLabel').pack(anchor='w')
        self.ips = ttk.Label(outer, text='IP: ...'); self.ips.pack(anchor='w', pady=(6, 2))
        self.pin_label = ttk.Label(outer, text='Código: ...'); self.pin_label.pack(anchor='w')
        ttk.Label(outer, text='Porta: 8765 • Mesmo Wi-Fi/hotspot ou VPN privada nos dois aparelhos.').pack(anchor='w', pady=(0, 4))
        ttk.Label(outer, text='ATENÇÃO: IP 10.x da operadora 5G pode não aceitar conexões externas. Use hotspot ou VPN privada.', style='Muted.TLabel', wraplength=620).pack(anchor='w', pady=(0, 8))
        row = ttk.Frame(outer); row.pack(fill='x', pady=5)
        ttk.Button(row, text='🌐 Painel no navegador', command=self.open_local, style='Primary.TButton').pack(side='left', padx=(0, 5))
        ttk.Button(row, text='📁 Arquivos recebidos', command=self.open_folder).pack(side='left')
        ttk.Button(row, text='📋 Copiar IP e PIN', command=self.copy_details).pack(side='left', padx=(6,0))
        ttk.Button(outer, text='🩺 Gerar diagnóstico TXT', command=self.save_diagnostic).pack(anchor='w',pady=(5,2))
        ttk.Checkbutton(outer, text='🖥️ Compartilhar tela deste Windows (com autorização)', variable=self.screen,
                        command=self.toggle_screen).pack(anchor='w', pady=(6, 12))
        quality = ttk.Frame(outer);quality.pack(fill='x',pady=(0,8))
        ttk.Label(quality,text='Qualidade da tela:', style='Muted.TLabel').pack(side='left',padx=(0,8))
        modes=ttk.Combobox(quality, textvariable=self.screen_mode, state='readonly',
                           values=['Econômico','Equilibrado','Nítido','Baixa latência'],width=15)
        modes.pack(side='left')
        modes.bind('<<ComboboxSelected>>',lambda _e:self.change_quality())
        ttk.Label(quality,text='• Até 12 fps no modo baixa latência',style='Muted.TLabel').pack(side='left',padx=8)
        ttk.Label(outer,text='📱 REDMI PAD 2 COMO TELA DO LEGION GO',style='Section.TLabel').pack(anchor='w',pady=(9,4))
        ttk.Label(outer,text='Espelhamento sem internet: veja a tela do Windows no tablet. Para estender de verdade (monitor adicional), o Windows precisa de um driver de monitor virtual compatível, que NÃO está incluído.',style='Muted.TLabel',wraplength=620).pack(anchor='w')
        ttk.Button(outer,text='📺 Instruções e link para o Redmi Pad 2',command=self.tablet_instructions,style='Primary.TButton').pack(anchor='w',pady=(5,10))
        ttk.Label(outer,text='🖱️ CONTROLE REMOTO DO WINDOWS',style='Section.TLabel').pack(anchor='w',pady=(12,4))
        ttk.Label(outer,text='Autorize por 15 minutos apenas quando quiser. Requer tela compartilhada + PIN de rede + código temporário separado.',style='Muted.TLabel',wraplength=580).pack(anchor='w')
        row=ttk.Frame(outer);row.pack(fill='x',pady=5)
        ttk.Button(row,text='✅ Permitir controle (15 min)',command=self.enable_control,style='Success.TButton').pack(side='left',padx=(0,6))
        ttk.Button(row,text='⛔ Bloquear agora',command=self.disable_control).pack(side='left')
        ttk.Label(outer,textvariable=self.control_status,foreground='#ffe5a3',wraplength=590).pack(anchor='w',pady=(4,9))
        ttk.Separator(outer).pack(fill='x', pady=7)
        ttk.Label(outer, text='📱  CONECTAR E CONTROLAR CELULAR OU TABLET', style='Section.TLabel').pack(anchor='w')
        row = ttk.Frame(outer); row.pack(fill='x', pady=5)
        ttk.Label(row, text='IP:').pack(side='left')
        ttk.Entry(row, textvariable=self.peer_ip, width=20).pack(side='left', padx=5, fill='x', expand=True)
        ttk.Label(row, text='Código:').pack(side='left')
        ttk.Entry(row, textvariable=self.peer_pin, width=10).pack(side='left', padx=5)
        row = ttk.Frame(outer); row.pack(fill='x', pady=5)
        ttk.Button(row, text='🔎 Procurar aparelhos', command=self.find_peers, style='Primary.TButton').pack(side='left', padx=(0, 6))
        ttk.Button(row, text='🔗 Conectar', command=self.connect, style='Success.TButton').pack(side='left', padx=(0, 6))
        ttk.Button(row, text='Abrir painel remoto', command=self.open_remote).pack(side='left')
        ttk.Label(outer, textvariable=self.remote_name).pack(anchor='w', pady=(4, 6))
        row = ttk.Frame(outer); row.pack(fill='x', pady=5)
        ttk.Button(row, text='📤 Enviar arquivos', command=self.send_files, style='Primary.TButton').pack(side='left', padx=(0, 6))
        ttk.Button(row, text='📁 Enviar pasta (ZIP)', command=self.send_folder).pack(side='left', padx=(0,6))
        ttk.Button(row, text='👁️ Ver tela remota', command=self.view_screen).pack(side='left')
        ttk.Button(row, text='🖱️ Controlar aparelho remoto', command=self.open_control).pack(side='left',padx=(6,0))
        self.progress = ttk.Progressbar(outer, orient='horizontal', mode='determinate', maximum=100)
        self.progress.pack(fill='x', pady=(5, 2))
        ttk.Label(outer, textvariable=self.progress_label, wraplength=590).pack(anchor='w', pady=(0, 4))
        ttk.Label(outer, textvariable=self.incoming_label, wraplength=590).pack(anchor='w', pady=(0, 4))
        ttk.Label(outer, text='MENSAGEM DE TEXTO').pack(anchor='w', pady=(8, 2))
        row = ttk.Frame(outer); row.pack(fill='x')
        ttk.Entry(row, textvariable=self.send_text).pack(side='left', fill='x', expand=True, padx=(0, 6))
        ttk.Button(row, text='📨 Enviar', command=self.send_message, style='Success.TButton').pack(side='left')
        ttk.Button(row, text='🧭 Ajuda de conexão', command=self.show_connection_help).pack(side='left', padx=(6,0))
        ttk.Label(outer, text='💬 MENSAGENS RECEBIDAS (AUTOMÁTICO)').pack(anchor='w', pady=(9, 4))
        self.inbox = tk.Text(outer, bg='#101e33', fg='#d6ecff', height=5,
                             relief='flat', wrap='word', state='disabled', font=('Segoe UI', 10))
        self.inbox.pack(fill='x')
        ttk.Label(outer, text='📋 ATIVIDADE / INSTRUÇÕES', style='Section.TLabel').pack(anchor='w', pady=(9, 4))
        self.log = tk.Text(outer, bg='#0b1020', fg='#dbeafe', insertbackground='white', height=8,
                           relief='flat', wrap='word', state='disabled', font=('Consolas', 9))
        self.log.pack(fill='both', expand=True)
        ttk.Label(outer, textvariable=self.status, wraplength=580).pack(anchor='w', pady=(8, 0))
        self.root.bind('<Return>', lambda e: self.send_message() if e.widget.winfo_class() in ('TEntry','Entry') else None)
        self._log('Dica: no celular, informe o IP deste Windows e o código acima. Ou faça o contrário.')
        self._log('Sem câmera? Use IP e código. Internet NÃO é necessária quando ambos estão no mesmo hotspot.')

    def _log(self, msg):
        self.log.configure(state='normal')
        self.log.insert('end', msg + '\n')
        self.log.see('end')
        self.log.configure(state='disabled')

    def _pump(self):
        try:
            while True:
                kind, val = self.messages.get_nowait()
                if kind == 'log': self._log(val)
                elif kind == 'status': self.status.set(val)
                elif kind == 'connected': self.remote_name.set('✅ ' + val)
                elif kind == 'found': self._choose_peer(val)
                elif kind == 'error': messagebox.showerror('ConectaTudo', val)
                elif kind == 'progress':
                    done, total, name, index, count, started = val
                    self.progress['value'] = min(100, done * 100 / max(1,total))
                    seconds = max(.1,time.monotonic() - started)
                    speed = done / seconds
                    eta = (total - done) / speed if speed > 0 else 0
                    self.progress_label.set(f'📤 {index}/{count}: {name} • {self._size(done)} / {self._size(total)} • {done*100/max(1,total):.0f}% • {self._size(speed)}/s • {eta:.0f}s restantes')
                elif kind == 'done':
                    self.progress['value'] = 100
                    self.progress_label.set(val)
        except queue.Empty:
            pass
        if self.host.control_code and not self.host.control_active():
            self.host.revoke_control()
            self.control_status.set('🔒 Controle remoto DESATIVADO (expirou ou tela parou)')
        if time.monotonic() - self._last_local >= .9:
            self._last_local = time.monotonic()
            self._refresh_local()
        if self.root.winfo_exists(): self.root.after(120, self._pump)

    @staticmethod
    def _size(n):
        n=float(n)
        if n<1024:return f'{n:.0f} B'
        if n<1048576:return f'{n/1024:.1f} KB'
        if n<1073741824:return f'{n/1048576:.1f} MB'
        return f'{n/1073741824:.2f} GB'

    def _refresh_local(self):
        with self.host.lock:
            messages=list(self.host.messages)
            files=list(self.host.files)
            transfers=list(self.host.transfers.values())
        for msg in messages:
            key=(msg.get('time'),msg.get('text'))
            if key not in self._seen_messages:
                self._seen_messages.add(key)
                self._log('💬 Recebido: ' + str(msg.get('text',''))[:250])
        for file in files:
            key=(file.get('time'),file.get('name'))
            if key not in self._seen_files:
                self._seen_files.add(key)
                self._log('📥 Arquivo recebido: ' + str(file.get('name','')))
        new_text='\n'.join(time.strftime('%H:%M:%S',time.localtime(m.get('time',0)/1000))+' • '+str(m.get('text','')) for m in messages[-20:])
        if self.inbox.get('1.0','end-1c') != new_text:
            self.inbox.configure(state='normal')
            self.inbox.delete('1.0','end')
            self.inbox.insert('end',new_text)
            self.inbox.see('end')
            self.inbox.configure(state='disabled')
        receiving=[t for t in transfers if t.get('status')=='receiving']
        if receiving:
            t=receiving[-1]
            self.incoming_label.set(f'📥 Recebendo {t["name"]}: {self._size(t["done"])} / {self._size(t["size"])} • {t["done"]*100/max(1,t["size"]):.0f}%')
        else:self.incoming_label.set('')

    def bg(self, fn):
        threading.Thread(target=fn, daemon=True).start()

    def refresh_status(self):
        self.ips.config(text='Endereço(s): ' + (', '.join(local_ips()) or '(rede não detectada)'))
        self.pin_label.config(text='Código de acesso (PIN): ' + self.host.pin)
        self.status.set('Servidor ativo na porta 8765 • Arquivos recebidos: ' + str(self.host.folder))

    def change_quality(self):
        self.host.screen_mode = self.screen_mode.get()
        self.status.set('🖥️ Qualidade de tela: ' + self.host.screen_mode)
        self._log('🖥️ Perfil de tela: '+self.host.screen_mode)

    def copy_details(self):
        details = 'IP: ' + ', '.join(local_ips()) + ' | PIN: ' + self.host.pin + ' | Porta: 8765'
        self.root.clipboard_clear()
        self.root.clipboard_append(details)
        self.status.set('✅ IP e PIN copiados para a área de transferência.')
        self._log('📋 Endereço e código copiados.')

    def tablet_instructions(self):
        if not self.host.sharing_screen:
            messagebox.showwarning('Tela do Redmi Pad 2', 'Primeiro marque Compartilhar tela deste Windows. Depois conecte o tablet ao mesmo Wi-Fi/hotspot do Legion Go.')
            return
        ips=local_ips()
        if not ips:
            messagebox.showwarning('Rede', 'Nenhum IP da rede encontrado. Verifique o Wi-Fi/hotspot.')
            return
        text='No Redmi Pad 2, abra ConectaTudo 1.6, informe o IP e o PIN deste Windows e toque em Modo tablet: espelhar Windows.\n\nOutra opção: navegador do tablet -> http://IP:8765/tablet?pin=CODIGO\n\nIP(s): '+', '.join(ips)+'\nPIN: '+self.host.pin+'\n\nIMPORTANTE: isto ESPELHA a tela atual. Não estende a área de trabalho do Windows; isso requer driver de monitor virtual, instalado e autorizado separadamente.\n\nUse somente rede confiável ou VPN privada.'
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        messagebox.showinfo('Espelhamento no Redmi Pad 2 • instruções copiadas',text)

    def save_diagnostic(self):
        path=filedialog.asksaveasfilename(title='Salvar relatório de conexão',defaultextension='.txt',initialfile='ConectaTudo_1.6_diagnostico.txt',filetypes=[('Texto','*.txt')])
        if not path:return
        peer=self.peer_ip.get().strip()
        lines=['ConectaTudo 1.6 - diagnóstico local', 'Sistema: '+sys.platform,'Servidor local: '+str(self.host.running), 'Porta: '+str(PORT),'IP(s) encontrados: '+', '.join(local_ips()), 'Tela compartilhada: '+str(self.host.sharing_screen),'Controle temporário ativo: '+str(self.host.control_active()),'IP remoto informado: '+peer,'Código remoto configurado: '+str(len(self.peer_pin.get().strip())==6),'Notas: para 5G/redes diferentes usar VPN privada. Nunca expor porta 8765 na Internet pública.','O relatório não inclui o PIN nem o código de controle.']
        Path(path).write_text('\n'.join(lines)+'\n',encoding='utf-8')
        self._log('🩺 Diagnóstico salvo em '+path)

    def show_connection_help(self):
        messagebox.showinfo('Como conectar • ConectaTudo 1.6',
            'OFFLINE: conecte celular e Legion Go ao mesmo Wi-Fi ou hotspot. '
            'Toque Procurar aparelhos. Se necessário, digite IP e PIN.\n\n'
            'ONLINE: use uma VPN privada entre os dois dispositivos (ex.: Tailscale). A descoberta automática só funciona na mesma rede. '
            'Informe o IP privado da VPN do outro aparelho (por exemplo 100.x.x.x) e o PIN. '
            'A VPN cifra o tráfego entre os dispositivos. Não exponha porta 8765 na internet pública.\n\n'
            'CONTROLE REMOTO: no Windows marque Compartilhar tela + Permitir controle. No Android: compartilhe a tela, habilite Acessibilidade e permita 15 min. Digite o código extra do destino no painel remoto.')

    def enable_control(self):
        if not self.host.sharing_screen:
            messagebox.showwarning('Antes de permitir','Marque primeiro Compartilhar tela deste Windows.')
            return
        if not messagebox.askyesno('Controle remoto temporário',
                'Permitir que alguém com o PIN e o código extra mova o mouse e digite neste Windows por até 15 minutos?\n\nConceda apenas a dispositivo confiável.\nNão exponha a porta 8765 na internet pública.'):
            return
        code=self.host.authorize_control(15)
        self.control_status.set('🟢 PERMITIDO até 15 minutos • Código extra: '+code)
        self._log('🖱️ Controle autorizado temporariamente. Não compartilhe o código com desconhecidos.')

    def disable_control(self):
        self.host.revoke_control()
        self.control_status.set('🔒 Controle remoto DESATIVADO')
        self._log('⛔ Controle remoto revogado.')

    def open_control(self):
        try: _,_,url=self.target('/control')
        except ValueError as e: return messagebox.showwarning('ConectaTudo',str(e))
        webbrowser.open(url)

    def toggle_screen(self):
        self.host.sharing_screen = self.screen.get()
        if not self.screen.get(): self.disable_control()
        self.status.set('🖥️ Compartilhamento de tela ' + ('ativo' if self.screen.get() else 'desligado'))
        self._log('Tela do Windows: ' + ('COMPARTILHAMENTO ATIVADO' if self.screen.get() else 'parado'))

    def open_local(self):
        webbrowser.open(f'http://127.0.0.1:{PORT}/?pin={self.host.pin}')

    def open_folder(self):
        self.host.folder.mkdir(parents=True, exist_ok=True)
        if sys.platform.startswith('win'): os.startfile(str(self.host.folder))
        else: self._log(str(self.host.folder))

    def target(self, path):
        ip = self.peer_ip.get().strip().replace('http://', '').replace('https://', '').split('/')[0].split(':')[0]
        pin = self.peer_pin.get().strip()
        if not ip or len(pin) != 6 or not pin.isdecimal():
            raise ValueError('Digite o IP do outro aparelho e seu código de 6 números.')
        # Bloqueia nomes com prefixos de URL, somente endereço IPv4/hostname local
        if any(x in ip for x in ('@', '?', '#', ' ')):
            raise ValueError('Endereço inválido')
        return ip, pin, f'http://{ip}:{PORT}{path}' + ('&' if '?' in path else '?') + 'pin=' + pin

    def connect(self):
        try: _,_,url = self.target('/api/info')
        except ValueError as e: return messagebox.showwarning('ConectaTudo', str(e))
        def task():
            try:
                with urllib.request.urlopen(url, timeout=7) as response:
                    data = json.load(response)
                self.messages.put(('connected', data.get('name', 'Dispositivo conectado')))
                self.messages.put(('log', 'Conexão confirmada com ' + data.get('name', 'dispositivo')))
            except Exception as e:
                self.messages.put(('error', 'Não conectou. Verifique IP, PIN, hotspot e firewall.\n' + str(e)))
        self.status.set('⏳ Testando conexão...')
        self.bg(task)

    def find_peers(self):
        self._log('🔎 Procurando dispositivos por até 3 segundos...')
        self.status.set('Procurando outros dispositivos na mesma rede...')
        self.bg(lambda: self.messages.put(('found', discover(3.2))))

    def _choose_peer(self, data):
        if not data:
            self.status.set('⚠️ Nada encontrado; tente o IP e o PIN.')
            self._log('Nada encontrado na busca automática. Informe IP e código manualmente.'); return
        popup = tk.Toplevel(self.root); popup.title('Dispositivos encontrados'); popup.geometry('440x220')
        lb = tk.Listbox(popup, font=('Segoe UI', 11))
        lb.pack(fill='both', expand=True, padx=10, pady=10)
        for dev in data: lb.insert('end', f"{dev['name']}  —  {dev['ip']}")
        def choose():
            sel = lb.curselection()
            if sel:
                self.peer_ip.set(data[sel[0]]['ip']); popup.destroy()
                self._log('IP preenchido. Digite o PIN mostrado no outro aparelho e clique Conectar.')
        ttk.Button(popup, text='Usar endereço selecionado', command=choose).pack(pady=(0, 8))

    def open_remote(self):
        try: _,_,url = self.target('/')
        except ValueError as e: return messagebox.showwarning('ConectaTudo', str(e))
        webbrowser.open(url)

    def view_screen(self):
        try: _,_,url = self.target('/screen')
        except ValueError as e: return messagebox.showwarning('ConectaTudo', str(e))
        webbrowser.open(url)

    def send_files(self):
        try: ip, pin, _ = self.target('/api/send')
        except ValueError as e: return messagebox.showwarning('ConectaTudo', str(e))
        files = filedialog.askopenfilenames(title='Arquivos para enviar')
        if not files: return
        self._send_paths(files)

    def send_folder(self):
        try: self.target('/api/send')
        except ValueError as e: return messagebox.showwarning('ConectaTudo', str(e))
        folder = filedialog.askdirectory(title='Escolha uma pasta para enviar compactada')
        if not folder: return
        self.status.set('⏳ Compactando pasta...')
        def create_and_send():
            try:
                name = Path(folder).name or 'Pasta'
                temp = tempfile.NamedTemporaryFile(suffix='.zip', prefix='ConectaTudo_', delete=False)
                temp.close()
                with zipfile.ZipFile(temp.name, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=2, allowZip64=True) as z:
                    for base, dirs, files in os.walk(folder):
                        dirs[:] = [x for x in dirs if not os.path.islink(os.path.join(base, x))]
                        for file in files:
                            full = Path(base) / file
                            if full.is_symlink(): continue
                            z.write(full, str(Path(name) / full.relative_to(folder)))
                self.messages.put(('log', '📁 Pasta compactada para envio: ' + name))
                self._send_paths([temp.name], display_names=[name + '.zip'], cleanup=[temp.name])
            except Exception as e:
                self.messages.put(('error', 'Não consegui preparar a pasta: ' + str(e)))
        self.bg(create_and_send)

    def _send_paths(self, files, display_names=None, cleanup=None):
        try: ip, pin, _ = self.target('/api/send')
        except ValueError as e:
            self.messages.put(('error', str(e)))
            return
        if self._busy:
            self.messages.put(('log', '⚠️ Aguarde terminar a transferência atual.'))
            if cleanup:
                for f in cleanup:
                    try: os.unlink(f)
                    except OSError: pass
            return
        self._busy = True
        self.messages.put(('status', '⏳ Preparando envio de arquivos...'))
        def task():
            try:
                total = sum(os.path.getsize(x) for x in files)
                done = 0
                started = time.monotonic()
                last_update = 0.0
                for index, file in enumerate(files,1):
                    size = os.path.getsize(file)
                    name = display_names[index-1] if display_names else Path(file).name
                    path = '/api/send?pin=' + pin + '&name=' + quote(name)
                    conn = http.client.HTTPConnection(ip, PORT, timeout=240)
                    try:
                        conn.putrequest('POST', path)
                        conn.putheader('Content-Type', 'application/octet-stream')
                        conn.putheader('Content-Length', str(size))
                        conn.endheaders()
                        with open(file, 'rb') as f:
                            while chunk := f.read(128 * 1024):
                                conn.send(chunk)
                                done += len(chunk)
                                now = time.monotonic()
                                if now - last_update > .15 or done == total:
                                    self.messages.put(('progress',(done,total,name,index,len(files),started)))
                                    last_update = now
                        res = conn.getresponse()
                        data = res.read(4096)
                        if res.status != 200: raise IOError(str(data[:300]))
                        self.messages.put(('log','✅ Enviado: '+name+f' ({self._size(size)})'))
                    finally: conn.close()
                self.messages.put(('done',f'✅ Transferência concluída: {len(files)} arquivo(s), {self._size(total)}'))
                self.messages.put(('status','Transferências concluídas.'))
            except Exception as e:
                self.messages.put(('error','Falha na transferência: '+str(e)))
            finally:
                self._busy = False
                for f in (cleanup or []):
                    try: os.unlink(f)
                    except OSError: pass
        self.bg(task)

    def send_message(self):
        msg = self.send_text.get().strip()
        if not msg: return
        try: _,_,url = self.target('/api/message')
        except ValueError as e: return messagebox.showwarning('ConectaTudo', str(e))
        self.send_text.set('')
        self.status.set('⏳ Enviando mensagem...')
        def task():
            try:
                req = urllib.request.Request(url, data=msg.encode(), method='POST',
                                             headers={'Content-Type': 'text/plain; charset=utf-8'})
                with urllib.request.urlopen(req, timeout=9) as res: res.read()
                self.messages.put(('log', '✉️ ' + msg[:180]))
                self.messages.put(('status','✅ Mensagem enviada.'))
            except Exception as e:
                self.messages.put(('error', 'Mensagem não enviada: ' + str(e)))
        self.bg(task)

    def close(self):
        try: self.host.stop()
        except Exception: pass
        self.root.destroy()

if __name__ == '__main__':
    root = tk.Tk(); App(root); root.mainloop()
