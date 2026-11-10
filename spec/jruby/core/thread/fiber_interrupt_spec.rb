describe 'Thread#raise with a finalized fiber' do
  # A finalized fiber runs its ensure on its own thread, while another fiber runs.
  it 'reaches the running fiber, not the finalized one' do
    events = Queue.new
    thread = Thread.new do
      finalized = Fiber.new do
        Fiber.yield
      ensure
        begin
          events << :finalized_ensure
          sleep 1
        rescue => e
          events << [:finalized, e.message]
        end
      end
      finalized.resume

      Fiber.new do
        finalized.send(:__finalize__)
        begin
          events << :running_sleep
          sleep
        rescue => e
          events << [:running, e.message]
        end
      end.resume
    end
    thread.report_on_exception = false

    seen = [events.pop, events.pop]
    expect(seen).to contain_exactly(:finalized_ensure, :running_sleep)
    thread.raise 'wake up'

    expect(thread.join(5)).to eq(thread)
    expect(events.pop).to eq([:running, 'wake up'])
  end
end

describe 'Thread#kill with a fiber finalized while the thread dies' do
  it 'does not wake the fiber that transferred away' do
    events = []
    thread = Thread.new do
      suspended = Fiber.new { Fiber.yield }
      Fiber.new do
        suspended.resume
        Thread.current.kill
      ensure
        events << :ensure_start
        suspended.send(:__finalize__)
        sleep 0.1
        events << :ensure_end
      end.transfer
    ensure
      events << :root_ensure
    end

    expect(thread.join(5)).to eq(thread)
    expect(events).to eq([:ensure_start, :ensure_end, :root_ensure])
  end
end

describe 'Thread#kill on the thread backing a fiber' do
  # Fiber threads show up in Thread.list, so code killing all other threads reaches them.
  # Rework or remove these once fiber threads leave Thread.list.
  it 'ends a suspended fiber, and the next resume returns nil' do
    ensured = []
    owner = Thread.new do
      before = Thread.list
      fiber = Fiber.new do
        Fiber.yield
        :second
      ensure
        ensured << :fiber
      end
      fiber.resume
      (Thread.list - before).each(&:kill)
      fiber.resume
    end

    expect(owner.join(5)).to eq(owner)
    expect(owner.value).to eq(nil)
    expect(ensured).to eq([:fiber])
  end

  it 'ends the running fiber, and its resume returns nil' do
    threads = Queue.new
    owner = Thread.new do
      before = Thread.list
      Fiber.new { threads << Thread.list - before; sleep }.resume
    end
    fiber_threads = threads.pop
    expect(fiber_threads.size).to eq(1)
    Thread.pass until owner.stop?
    fiber_threads.each(&:kill)

    expect(owner.join(5)).to eq(owner)
    expect(owner.value).to eq(nil)
  end
end
