# PyInstaller needs freeze_support before app imports/argument parsing so a
# bounded JPEG decoder child cannot recursively launch a second bridge server.
import multiprocessing

if __name__ == "__main__":
    multiprocessing.freeze_support()
    from open_eos_bridge.standalone import main

    main()
