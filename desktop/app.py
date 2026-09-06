"""Windows GUI; workers communicate with Tk only through a queue."""
import queue
import threading
import tkinter as tk
from tkinter import filedialog, ttk
from processor import discover, process


def main():
    root = tk.Tk()
    root.title('Lengua Reacción · Preparar episodios')
    root.geometry('900x620')
    inputs = []
    events = queue.Queue()
    destination = tk.StringVar()
    device = tk.StringVar(value='cuda')
    listing = tk.Listbox(root, height=9)
    listing.pack(fill='x', padx=16, pady=12)
    controls = ttk.Frame(root)
    controls.pack(fill='x', padx=16)

    def add(paths):
        for path in paths:
            if path not in inputs:
                inputs.append(path)
                listing.insert('end', path)

    ttk.Button(controls, text='Añadir vídeos', command=lambda: add(filedialog.askopenfilenames())).pack(side='left')
    ttk.Button(controls, text='Añadir carpeta', command=lambda: add(filter(None, [filedialog.askdirectory()]))).pack(side='left')
    ttk.Button(controls, text='Carpeta destino', command=lambda: destination.set(filedialog.askdirectory() or destination.get())).pack(side='left')
    ttk.Entry(root, textvariable=destination).pack(fill='x', padx=16, pady=12)
    ttk.Label(root, text='Whisper Large v3 · Traducción local · Sin consumo de API').pack()
    ttk.Combobox(root, textvariable=device, values=['cuda', 'cpu'], state='readonly').pack()
    output = tk.Text(root, height=17)
    output.pack(fill='both', expand=True, padx=16, pady=12)

    def start():
        if not inputs or not destination.get():
            events.put('Selecciona vídeos y una carpeta destino.')
            return
        button.config(state='disabled')
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

    def poll():
        while not events.empty():
            message = events.get_nowait()
            if message is None:
                button.config(state='normal')
            else:
                output.insert('end', message + '\n')
                output.see('end')
        root.after(150, poll)
    poll()
    root.mainloop()


if __name__ == '__main__':
    main()
