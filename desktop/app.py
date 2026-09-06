"""Windows GUI; workers communicate with Tk only through a queue."""
import queue
import threading
import tkinter as tk
from tkinter import filedialog, ttk, messagebox
import os
from processor import discover, process


def main():
    root = tk.Tk()
    root.title('Lengua Reacción · Preparar episodios')
    root.geometry('1000x720')
    root.minsize(850, 600)
    style = ttk.Style(root)
    style.theme_use('clam')
    style.configure('TButton', font=('Segoe UI', 11), padding=8)
    style.configure('TLabel', font=('Segoe UI', 11))
    ttk.Label(root, text='Prepara tu próxima sesión', font=('Segoe UI', 24, 'bold')).pack(anchor='w', padx=16, pady=12)
    ttk.Label(root, text='1. Añade vídeos o una carpeta de serie · 2. Elige destino · 3. Genera tus paquetes').pack(anchor='w', padx=16)
    inputs = []
    events = queue.Queue()
    destination = tk.StringVar(value=os.path.join(os.path.expanduser('~'), 'OneDrive', 'cosas', 'doblados'))
    device = tk.StringVar(value='cuda')
    listing = tk.Listbox(root, height=7, font=('Segoe UI', 10))
    listing.pack(fill='x', padx=16, pady=12)
    controls = ttk.Frame(root)
    controls.pack(fill='x', padx=16)

    def add(paths):
        if str(button['state']) == 'disabled':
            return
        for path in paths:
            if path not in inputs:
                inputs.append(path)
                listing.insert('end', path)

    ttk.Button(controls, text='Añadir vídeos', command=lambda: add(filedialog.askopenfilenames())).pack(side='left')
    ttk.Button(controls, text='Añadir carpeta', command=lambda: add(filter(None, [filedialog.askdirectory()]))).pack(side='left')
    ttk.Button(controls, text='Carpeta destino', command=lambda: destination.set(filedialog.askdirectory() or destination.get())).pack(side='left')
    def remove():
        if str(button['state']) != 'disabled':
            for index in reversed(listing.curselection()):
                inputs.pop(index)
                listing.delete(index)
    ttk.Button(controls, text='Quitar selección', command=remove).pack(side='left')
    ttk.Entry(root, textvariable=destination).pack(fill='x', padx=16, pady=12)
    ttk.Label(root, text='Whisper Large v3 · Traducción local · Sin consumo de API').pack()
    ttk.Combobox(root, textvariable=device, values=['cuda', 'cpu'], state='readonly').pack()
    ttk.Label(root, text='cuda = GPU NVIDIA · cpu = procesador (más lento). Primera ejecución: descarga de modelos.').pack()
    progress = ttk.Progressbar(root, mode='indeterminate')
    progress.pack(fill='x', padx=16, pady=8)
    output = tk.Text(root, height=17)
    output.pack(fill='both', expand=True, padx=16, pady=12)

    def start():
        if not inputs or not destination.get():
            events.put('Selecciona vídeos y una carpeta destino.')
            return
        button.config(state='disabled')
        progress.start()
        selected, target, accelerator = list(inputs), destination.get(), device.get()

        def worker():
            try:
                videos = discover(selected)
                events.put(f'{len(videos)} vídeos en la cola.')
                for video in videos:
                    try:
                        process(video, target, device=accelerator, log=events.put)
                    except Exception as error:
                        events.put(f'ERROR {video.name}: {error}')
            finally:
                events.put(None)
        threading.Thread(target=worker, daemon=True).start()

    button = ttk.Button(root, text='Preparar episodios', command=start)
    button.pack(pady=8)
    ttk.Button(root, text='Abrir carpeta de resultados', command=lambda: os.startfile(destination.get()) if os.path.isdir(destination.get()) else None).pack(pady=4)
    def close():
        if str(button['state']) == 'disabled':
            messagebox.showinfo('Procesamiento activo', 'Espera a que termine la cola antes de cerrar.')
        else:
            root.destroy()
    root.protocol('WM_DELETE_WINDOW', close)

    def poll():
        while not events.empty():
            message = events.get_nowait()
            if message is None:
                progress.stop()
                button.config(state='normal')
            else:
                output.insert('end', message + '\n')
                output.see('end')
        root.after(150, poll)
    poll()
    root.mainloop()


if __name__ == '__main__':
    main()
