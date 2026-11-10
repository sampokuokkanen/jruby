require_relative '../../spec_helper'
require_relative 'fixtures/classes'

describe "Thread#exit" do
  it "is an alias of Thread#kill" do
    Thread.instance_method(:exit).should == Thread.instance_method(:kill)
  end
end

describe "Thread#exit!" do
  it "needs to be reviewed for spec completeness"
end

describe "Thread.exit" do
  it "causes the current thread to exit" do
    thread = Thread.new { Thread.exit; sleep }
    thread.join
    thread.status.should == false
  end

  it "exits the current thread when called in a Fiber" do
    log = []
    thread = Thread.new do
      Fiber.new { Thread.exit; log << :fiber }.resume
      log << :thread
    end
    thread.join
    thread.status.should == false
    log.should == []
  end
end
