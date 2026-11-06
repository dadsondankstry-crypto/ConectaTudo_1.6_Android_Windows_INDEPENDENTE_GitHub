"""Local-only smoke tests for the Windows host, no GUI or Internet required."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0,str(Path(__file__).resolve().parents[1] / 'windows'))
from host import DeviceHost, PORT

class ProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory(prefix='conectatudo_')
        cls.server=DeviceHost(folder=cls.tmp.name)
        cls.server.start()
        cls.base=f'http://127.0.0.1:{PORT}'

    @classmethod
    def tearDownClass(cls):
        cls.server.stop()
        cls.tmp.cleanup()

    def get(self,path):
        with urlopen(self.base+path+('&' if '?' in path else '?')+'pin='+self.server.pin,timeout=8) as r:
            return json.load(r)

    def post(self,path,body=b'',headers=None):
        r=Request(self.base+path+('&' if '?' in path else '?')+'pin='+self.server.pin,body,method='POST',headers=headers or {})
        with urlopen(r,timeout=8) as response: return json.load(response)

    def test_tablet_viewer_and_authentication(self):
        with urlopen(self.base+'/tablet',timeout=8) as r:
            html=r.read().decode('utf-8')
        self.assertIn('Espelhamento',html)
        self.assertIn('NÃO estende',html)
        with self.assertRaises(HTTPError) as error:
            urlopen(self.base+'/api/info?pin=000000' if self.server.pin!='000000' else self.base+'/api/info?pin=111111',timeout=8)
        self.assertEqual(error.exception.code,403)

    def test_messages(self):
        self.post('/api/message','mensagem teste'.encode())
        self.assertEqual(self.get('/api/messages')['messages'][-1]['text'],'mensagem teste')

    def test_control_is_disabled(self):
        self.assertFalse(self.get('/api/control/status')['enabled'])
        with self.assertRaises(HTTPError) as error:
            self.post('/api/control/input',json.dumps({'action':'click'}).encode())
        self.assertEqual(error.exception.code,403)

    def test_temporary_control_gate(self):
        # PIN de 6 digitos sozinho nao basta; um codigo separado e necessario.
        host=self.server
        host.sharing_screen=True
        try:
            code=host.authorize_control(15)
            self.assertRegex(code,r'^\d{12}$')
            self.assertFalse(host.check_control('123456789012' if code!='123456789012' else '000000000000'))
            self.assertTrue(host.check_control(code))
            host.revoke_control()
            self.assertFalse(host.check_control(code))
        finally:
            host.sharing_screen=False

    def test_shared_capture_cache(self):
        # Em sistemas sem captura de tela (CI), simula um quadro so para testar fanout.
        from unittest.mock import patch
        host=self.server
        with patch.object(host,'_capture_jpeg',return_value=b'JPEG-test'):
            host.sharing_screen=True
            try:
                last=host._frame_id
                import time
                deadline=time.monotonic()+3
                while time.monotonic()<deadline and host._frame_id<=last:
                    time.sleep(.04)
                self.assertGreater(host._frame_id,last)
                frame_id,frame=host.wait_frame(last,0.1)
                self.assertEqual(frame,b'JPEG-test')
                self.assertGreater(frame_id,last)
            finally:
                host.sharing_screen=False

    def test_resumable_upload(self):
        content=b'arquivo teste' * 60000
        identity='upTESTabcdef0123456789'
        item={'id':identity,'name':'smoke.txt','size':len(content)}
        self.assertEqual(self.post('/api/upload/init',json.dumps(item).encode())['offset'],0)
        first=262144
        self.assertEqual(self.post('/api/upload/chunk?id='+identity+'&offset=0',content[:first])['offset'],first)
        self.assertEqual(self.get('/api/upload/status?id='+identity)['offset'],first)
        self.assertEqual(self.post('/api/upload/init',json.dumps(item).encode())['offset'],first)
        self.assertEqual(self.post('/api/upload/chunk?id='+identity+'&offset='+str(first),content[first:])['offset'],len(content))
        self.assertTrue(self.post('/api/upload/finish?id='+identity)['ok'])
        self.assertEqual((Path(self.tmp.name)/'smoke.txt').read_bytes(),content)
        self.assertEqual(len(self.get('/api/files')['files']),1)

if __name__=='__main__':unittest.main()
